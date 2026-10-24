package com.findlocally.api.messaging;

import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@Component
@ConditionalOnProperty(name = "app.sqs.enabled", havingValue = "true")
public class OutboxPublisher {
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final SqsClient sqs;
    private final String queueUrl;
    private final int batchSize, maxAttempts, retryDelay;

    public OutboxPublisher(NamedParameterJdbcTemplate jdbc, TransactionTemplate transactions, SqsClient sqs,
            @Value("${app.sqs.queue-url}") String queueUrl,
            @Value("${app.outbox.batch-size}") int batchSize,
            @Value("${app.outbox.max-attempts}") int maxAttempts,
            @Value("${app.outbox.retry-delay-seconds}") int retryDelay) {
        if (queueUrl.isBlank() || batchSize < 1 || maxAttempts < 1 || retryDelay < 1)
            throw new IllegalArgumentException("Configure SQS queue URL and positive outbox settings");
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryDelay = retryDelay;
    }

    private record Event(UUID id, UUID aggregateId, String payload, int attempts) {}

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms}")
    public void publishDue() {
        for (int i = 0; i < batchSize; i++) {
            if (!Boolean.TRUE.equals(transactions.execute(status -> publishOne()))) break;
        }
    }

    private boolean publishOne() {
        // Lock one row until its outcome commits. SKIP LOCKED supports multiple application instances.
        // A process crash rolls back the lock and leaves the event available for another attempt.
        var events = jdbc.query("""
                SELECT id, aggregate_id, payload, attempts FROM outbox_events
                WHERE status IN ('PENDING', 'RETRY') AND next_attempt_at <= now()
                ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                """, new MapSqlParameterSource(), (rs, row) -> new Event(rs.getObject("id", UUID.class),
                rs.getObject("aggregate_id", UUID.class), rs.getString("payload"), rs.getInt("attempts")));
        if (events.isEmpty()) return false;
        var event = events.getFirst();
        var parameters = new MapSqlParameterSource("id", event.id());
        String messageId;
        try {
            var request = SendMessageRequest.builder().queueUrl(queueUrl).messageBody(event.payload());
            if (queueUrl.endsWith(".fifo")) request.messageGroupId(event.aggregateId().toString())
                    .messageDeduplicationId(event.id().toString());
            messageId = sqs.sendMessage(request.build()).messageId();
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE outbox_events SET status = :status, attempts = attempts + 1,
                        next_attempt_at = now() + (:delay * interval '1 second'), last_error = :error
                    WHERE id = :id
                    """, parameters.addValue("status", event.attempts() + 1 >= maxAttempts ? "FAILED" : "RETRY")
                    .addValue("delay", retryDelay)
                    .addValue("error", exception.getClass().getSimpleName()));
            LoggerFactory.getLogger(getClass()).warn("Outbox event {} send failed ({})", event.id(),
                    exception.getClass().getSimpleName());
            return true;
        }
        // DB failure after send must roll back: delivery may repeat with the same eventId.
        jdbc.update("""
                UPDATE outbox_events SET status = 'SENT', attempts = attempts + 1,
                    sent_at = now(), sqs_message_id = :messageId, last_error = NULL WHERE id = :id
                """, parameters.addValue("messageId", messageId));
        return true;
    }
}
