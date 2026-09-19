package com.zachary.transportation_reliability_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded retry, retention, and backpressure settings for the Kafka outbox. */
@ConfigurationProperties(prefix = "app.kafka-outbox")
public record KafkaOutboxProperties(
        int maxPendingEvents,
        int dispatchBatchSize,
        int maxAttempts,
        long retryBaseSeconds,
        long retryMaxSeconds,
        long sendTimeoutSeconds
) {

    public KafkaOutboxProperties {
        maxPendingEvents = Math.max(1_000, maxPendingEvents);
        dispatchBatchSize = Math.max(1, Math.min(dispatchBatchSize, 1_000));
        maxAttempts = Math.max(1, maxAttempts);
        retryBaseSeconds = Math.max(1, retryBaseSeconds);
        retryMaxSeconds = Math.max(retryBaseSeconds, retryMaxSeconds);
        sendTimeoutSeconds = Math.max(1, sendTimeoutSeconds);
    }
}
