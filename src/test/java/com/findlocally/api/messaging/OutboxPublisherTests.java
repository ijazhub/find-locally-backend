package com.findlocally.api.messaging;

import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboxPublisherTests {
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final TransactionTemplate tx = mock(TransactionTemplate.class);
    private final SqsClient sqs = mock(SqsClient.class);
    private final UUID id = UUID.randomUUID();
    private final UUID business = UUID.randomUUID();
    private final String payload = "{\"eventId\":\"" + id + "\"}";

    @SuppressWarnings({"unchecked", "rawtypes"})
    private OutboxPublisher publisher(String url, int previousAttempts) throws Exception {
        reset(tx, jdbc);
        when(tx.execute(any())).thenAnswer(call -> ((TransactionCallback) call.getArgument(0)).doInTransaction(null));
        var rs = mock(ResultSet.class);
        when(rs.getObject("id", UUID.class)).thenReturn(id);
        when(rs.getObject("aggregate_id", UUID.class)).thenReturn(business);
        when(rs.getString("payload")).thenReturn(payload);
        when(rs.getInt("attempts")).thenReturn(previousAttempts);
        when(jdbc.query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenAnswer(call -> List.of(((RowMapper) call.getArgument(2)).mapRow(rs, 0)));
        return new OutboxPublisher(jdbc, tx, sqs, url, 1, 3, 30);
    }

    @Test void sendsExactJsonAndMarksSentWithMessageId() throws Exception {
        var publisher = publisher("https://sqs.ap-south-1.amazonaws.com/123/business", 0);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        publisher.publishDue();
        verify(sqs).sendMessage(argThat((SendMessageRequest r) -> payload.equals(r.messageBody())
                && r.messageDeduplicationId() == null));
        verify(jdbc).update(contains("status = 'SENT'"), argThat((MapSqlParameterSource p) ->
                "message-1".equals(p.getValue("messageId"))));
    }

    @Test void fifoUsesStableEventIdAndBusinessGroup() throws Exception {
        var publisher = publisher("https://sqs.ap-south-1.amazonaws.com/123/business.fifo", 0);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        publisher.publishDue();
        verify(sqs).sendMessage(argThat((SendMessageRequest r) -> id.toString().equals(r.messageDeduplicationId())
                && business.toString().equals(r.messageGroupId())));
    }

    @Test void retriesFailuresAndStopsAtAttemptLimit() throws Exception {
        var publisher = publisher("https://sqs.ap-south-1.amazonaws.com/123/business", 0);
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenThrow(new IllegalStateException("sensitive"));
        publisher.publishDue();
        verify(jdbc).update(contains("status = :status"), argThat((MapSqlParameterSource p) ->
                "RETRY".equals(p.getValue("status")) && "IllegalStateException".equals(p.getValue("error"))));
        clearInvocations(jdbc);
        publisher = publisher("https://sqs.ap-south-1.amazonaws.com/123/business", 2);
        publisher.publishDue();
        verify(jdbc).update(contains("status = :status"), argThat((MapSqlParameterSource p) ->
                "FAILED".equals(p.getValue("status"))));
    }

    @Test void missingQueueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new OutboxPublisher(jdbc, tx, sqs, "", 1, 3, 30));
    }

    @Test void emptyOutboxDoesNotSend() throws Exception {
        var publisher = publisher("https://sqs.ap-south-1.amazonaws.com/123/business", 0);
        doReturn(List.of()).when(jdbc).query(anyString(), any(MapSqlParameterSource.class), any(RowMapper.class));
        publisher.publishDue();
        verifyNoInteractions(sqs);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test void databaseFailureAfterSendPropagatesForTransactionRollback() throws Exception {
        var publisher = publisher("https://sqs.ap-south-1.amazonaws.com/123/business", 0);
        when(sqs.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("message-1").build());
        when(jdbc.update(contains("status = 'SENT'"), any(MapSqlParameterSource.class)))
                .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("DB disconnected"));
        assertThrows(org.springframework.dao.DataAccessResourceFailureException.class, publisher::publishDue);
        verify(jdbc, never()).update(contains("status = :status"), any(MapSqlParameterSource.class));
    }
}
