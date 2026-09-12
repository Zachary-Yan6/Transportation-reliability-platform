package com.zachary.transportation_reliability_platform.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.event.TripUpdateEvent;
import com.zachary.transportation_reliability_platform.entity.AppUser;
import com.zachary.transportation_reliability_platform.security.JwtService;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the production path against disposable infrastructure: Flyway
 * migrations, Kafka, PostgreSQL, Redis, and HTTP controllers all run here.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RealtimePipelineIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("transit_reliability")
            .withUsername("transit")
            .withPassword("transit_test_password");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(
            // This is the image family Testcontainers 1.21 configures and
            // probes directly; it avoids the incompatible Confluent launcher.
            DockerImageName.parse("apache/kafka:3.8.0")
    );

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:7.4-alpine")
    ).withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.task.scheduling.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TripUpdateEventProducer tripUpdateEventProducer;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @LocalServerPort
    private int port;

    @Test
    void kafkaEventIsPersistedIdempotentlyProjectedToRedisAndExposedByBothApis() throws Exception {
        StaticReferences references = insertStaticReferences();
        UUID eventId = UUID.randomUUID();
        TripUpdateEvent event = new TripUpdateEvent(
                eventId,
                references.feedVersionId(),
                "TRIP-IT",
                "STOP-IT",
                1,
                180,
                Instant.parse("2026-09-11T18:00:00Z")
        );

        // Send the same ID twice: PostgreSQL's unique event_id must make this
        // at-least-once Kafka delivery idempotent.
        tripUpdateEventProducer.publish(event);
        tripUpdateEventProducer.publish(event);

        awaitUntil("the Kafka consumer to persist one observation", () ->
                observationCount(eventId) == 1
        );
        awaitUntil("the Redis live-state consumer to update the API", () ->
                getJson("/api/v1/trips/" + references.tripId() + "/live-delays")
                        .size() == 1
        );

        JsonNode history = getJson("/api/v1/trips/" + references.tripId() + "/delays?limit=10");
        JsonNode liveState = getJson("/api/v1/trips/" + references.tripId() + "/live-delays");

        assertThat(history).hasSize(1);
        assertThat(history.get(0).path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(history.get(0).path("externalTripId").asText()).isEqualTo("TRIP-IT");
        assertThat(history.get(0).path("externalStopId").asText()).isEqualTo("STOP-IT");
        assertThat(history.get(0).path("delaySeconds").asInt()).isEqualTo(180);
        assertThat(liveState).hasSize(1);
        assertThat(liveState.get(0).path("eventId").asText()).isEqualTo(eventId.toString());
        assertThat(liveState.get(0).path("source").asText()).isEqualTo("NTA_REALTIME");
        assertThat(liveState.get(0).path("delaySeconds").asInt()).isEqualTo(180);
        assertThat(observationCount(eventId)).isEqualTo(1);
    }

    private StaticReferences insertStaticReferences() {
        Long feedVersionId = jdbcTemplate.queryForObject("""
                INSERT INTO feed_versions (source_uri, checksum, effective_from, lifecycle_status)
                VALUES ('integration-test', 'integration-test-checksum', DATE '2026-09-11', 'ACTIVE')
                RETURNING id
                """, Long.class);
        Long routeId = jdbcTemplate.queryForObject("""
                INSERT INTO routes (feed_version_id, external_route_id, short_name, long_name, route_type)
                VALUES (?, 'ROUTE-IT', 'IT', 'Integration route', 3)
                RETURNING id
                """, Long.class, feedVersionId);
        Long stopId = jdbcTemplate.queryForObject("""
                INSERT INTO stops (feed_version_id, external_stop_id, stop_name, latitude, longitude)
                VALUES (?, 'STOP-IT', 'Integration stop', 53.3498, -6.2603)
                RETURNING id
                """, Long.class, feedVersionId);
        Long tripId = jdbcTemplate.queryForObject("""
                INSERT INTO trips (feed_version_id, route_id, external_trip_id, service_id)
                VALUES (?, ?, 'TRIP-IT', 'WEEKDAY')
                RETURNING id
                """, Long.class, feedVersionId, routeId);
        jdbcTemplate.update("""
                INSERT INTO stop_times (trip_id, stop_id, stop_sequence, arrival_seconds, departure_seconds)
                VALUES (?, ?, 1, 64800, 64800)
                """, tripId, stopId);

        return new StaticReferences(feedVersionId, tripId);
    }

    private int observationCount(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM trip_stop_delay_observations WHERE event_id = ?",
                Integer.class,
                eventId
        );
        return count == null ? 0 : count;
    }

    private JsonNode getJson(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(userToken());
        String response = restTemplate.exchange(
                "http://localhost:" + port + path,
                HttpMethod.GET,
                new HttpEntity<Void>(headers),
                String.class
        ).getBody();
        try {
            return objectMapper.readTree(response);
        } catch (java.io.IOException exception) {
            throw new AssertionError("Expected JSON response for " + path, exception);
        }
    }

    private String userToken() {
        AppUser user = new AppUser();
        user.setEmail("integration-user@example.test");
        user.setRole("USER");
        return jwtService.issue(user);
    }

    private void awaitUntil(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + description, exception);
            }
        }
        throw new AssertionError("Timed out waiting for " + description);
    }

    private record StaticReferences(Long feedVersionId, Long tripId) { }
}
