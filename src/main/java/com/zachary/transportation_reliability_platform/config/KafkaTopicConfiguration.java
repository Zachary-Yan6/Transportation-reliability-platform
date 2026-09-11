package com.zachary.transportation_reliability_platform.config;

import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates application-owned Kafka topics when the Spring application starts.
 */
@Configuration
public class KafkaTopicConfiguration {

    @Bean
    public NewTopic tripUpdateEventsTopic() {
        return new NewTopic(
                TripUpdateEventProducer.TOPIC,
                1,
                (short) 1
        );
    }

    @Bean
    public NewTopic vehiclePositionEventsTopic() {
        return new NewTopic(
                VehiclePositionEventProducer.TOPIC,
                1,
                (short) 1
        );
    }
}
