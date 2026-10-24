package com.findlocally.api.messaging;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.sqs.enabled", havingValue = "true")
public class SqsConfiguration {
    @Bean(destroyMethod = "close")
    SqsClient sqsClient(@Value("${app.sqs.region}") String region,
            @Value("${app.sqs.endpoint:}") String endpoint,
            @Value("${app.sqs.request-timeout-seconds}") long timeout) {
        if (region.isBlank() || timeout < 1) throw new IllegalArgumentException("Invalid SQS region or timeout");
        var builder = SqsClient.builder().region(Region.of(region))
                .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(timeout))
                        .apiCallAttemptTimeout(Duration.ofSeconds(timeout)));
        if (!endpoint.isBlank()) builder.endpointOverride(URI.create(endpoint));
        return builder.build();
    }
}
