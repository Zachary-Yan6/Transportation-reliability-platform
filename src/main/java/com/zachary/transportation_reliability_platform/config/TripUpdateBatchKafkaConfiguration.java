package com.zachary.transportation_reliability_platform.config;

import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Supplies a dedicated batch listener for historical trip-delay persistence.
 * Other consumers keep their record-by-record listeners because their Redis
 * projections have different freshness and ordering requirements.
 */
@Configuration
public class TripUpdateBatchKafkaConfiguration {

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, TripUpdateEvent>
    tripUpdateBatchKafkaListenerContainerFactory(
            ConsumerFactory<?, ?> consumerFactory,
            @Value("${app.nta.realtime-polling.consumer-batch-size:250}") int batchSize
    ) {
        ConcurrentKafkaListenerContainerFactory<String, TripUpdateEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        Map<String, Object> consumerProperties = new HashMap<>(
                consumerFactory.getConfigurationProperties()
        );
        // max.poll.records bounds a database transaction and one SQL INSERT.
        consumerProperties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, batchSize);
        factory.setConsumerFactory(new org.springframework.kafka.core.DefaultKafkaConsumerFactory<>(
                consumerProperties
        ));
        factory.setBatchListener(true);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        return factory;
    }
}
