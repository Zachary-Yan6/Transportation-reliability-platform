package com.zachary.transportation_reliability_platform.config;

import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TripUpdateBatchKafkaConfigurationUnitTest {

    @Test
    void dedicatedTripUpdateFactoryUsesBoundedBatchAcknowledgements() {
        @SuppressWarnings("unchecked")
        ConsumerFactory<String, TripUpdateEvent> sourceFactory = mock(ConsumerFactory.class);
        when(sourceFactory.getConfigurationProperties()).thenReturn(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9093"
        ));

        ConcurrentKafkaListenerContainerFactory<String, TripUpdateEvent> factory =
                new TripUpdateBatchKafkaConfiguration()
                        .tripUpdateBatchKafkaListenerContainerFactory(sourceFactory, 250);

        assertThat(factory.isBatchListener()).isTrue();
        assertThat(factory.getContainerProperties().getAckMode())
                .isEqualTo(ContainerProperties.AckMode.BATCH);
    }
}
