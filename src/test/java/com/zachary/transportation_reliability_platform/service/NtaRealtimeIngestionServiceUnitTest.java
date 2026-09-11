package com.zachary.transportation_reliability_platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.RealtimeStopTimeMatchRow;
import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.mapper.StopTimeMapper;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeIngestionService;
import com.zachary.transportation_reliability_platform.service.producer.TripUpdateEventProducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests extraction, static-feed matching, limits, and Kafka batch boundaries. */
class NtaRealtimeIngestionServiceUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void publishesOnlyMatchedUpdatesAndCountsEverySkipReason() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        TripUpdateEventProducer producer = mock(TripUpdateEventProducer.class);
        StopTimeMapper mapper = mock(StopTimeMapper.class);
        when(client.fetchRawFeed()).thenReturn(feed());
        when(mapper.findRealtimeMatches(eq(5L), anyList(), anyList()))
                .thenReturn(List.of(match("TRIP-1", "STOP-1", 1, 11L, 21L)));
        NtaRealtimeIngestionService service = service(client, producer, mapper);

        NtaRealtimeIngestionResponse response = service.publishValidUpdates(
                5L, 0, "  ROUTE-1  ", 10
        );

        assertThat(response.feedTimestamp()).isEqualTo(Instant.ofEpochSecond(1_789_058_972L));
        assertThat(response.scannedStopTimeUpdates()).isEqualTo(3);
        assertThat(response.publishedEventCount()).isEqualTo(1);
        assertThat(response.skippedUpdateCount()).isEqualTo(2);
        assertThat(response.publishedEvents()).singleElement().satisfies(event -> {
            assertThat(event.externalTripId()).isEqualTo("TRIP-1");
            assertThat(event.externalStopId()).isEqualTo("STOP-1");
            assertThat(event.delaySeconds()).isEqualTo(30);
            assertThat(event.resolvedTripId()).isEqualTo(11L);
            assertThat(event.resolvedStopId()).isEqualTo(21L);
        });
        verify(producer).publishBatch(response.publishedEvents());
    }

    @Test
    void appliesManualLimitAfterMatchingAndSplitsPublishedEventsIntoBatches() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        TripUpdateEventProducer producer = mock(TripUpdateEventProducer.class);
        StopTimeMapper mapper = mock(StopTimeMapper.class);
        when(client.fetchRawFeed()).thenReturn(feed());
        when(mapper.findRealtimeMatches(eq(5L), anyList(), anyList())).thenReturn(List.of(
                match("TRIP-1", "STOP-1", 1, 11L, 21L),
                match("TRIP-1", "STOP-2", 2, 11L, 22L)
        ));
        NtaRealtimeIngestionService service = service(client, producer, mapper);

        NtaRealtimeIngestionResponse limited = service.publishValidUpdates(
                5L, 1, null, 1
        );

        assertThat(limited.publishedEventCount()).isEqualTo(1);
        assertThat(limited.skippedUpdateCount()).isEqualTo(1);
        verify(producer).publishBatch(limited.publishedEvents());

        NtaRealtimeIngestionResponse unbounded = service.publishValidUpdates(
                5L, 0, " ", 1
        );
        assertThat(unbounded.publishedEventCount()).isEqualTo(2);
        verify(producer, times(3)).publishBatch(anyList());
    }

    @Test
    void emptyCandidatesAvoidDatabaseAndKafkaAndBadInputsAreRejected() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        TripUpdateEventProducer producer = mock(TripUpdateEventProducer.class);
        StopTimeMapper mapper = mock(StopTimeMapper.class);
        when(client.fetchRawFeed()).thenReturn("{\"entity\":[]}");
        NtaRealtimeIngestionService service = service(client, producer, mapper);

        NtaRealtimeIngestionResponse empty = service.publishValidUpdates(5L, 0);
        assertThat(empty.publishedEvents()).isEmpty();
        verify(mapper, never()).findRealtimeMatches(eq(5L), anyList(), anyList());
        verify(producer, never()).publishBatch(anyList());

        assertThatThrownBy(() -> service.publishValidUpdates(5L, -1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.publishValidUpdates(5L, 1, null, 0))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.publishValidUpdates(5L, 1, null, 1_001))
                .isInstanceOf(BusinessException.class);

        when(client.fetchRawFeed()).thenReturn("not-json");
        assertThatThrownBy(() -> service.publishValidUpdates(5L, 1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingTimestampNonTripEntitiesAndIncompleteCandidatesAreSafelySkipped() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        TripUpdateEventProducer producer = mock(TripUpdateEventProducer.class);
        StopTimeMapper mapper = mock(StopTimeMapper.class);
        when(client.fetchRawFeed()).thenReturn("""
                {"entity":[
                  {"id":"not-a-trip-update"},
                  {"trip_update":{"trip":{},"stop_time_update":[
                    {},
                    {"stop_id":"S1","stop_sequence":1,"arrival":{"delay":10}}
                  ]}}
                ]}
                """);
        NtaRealtimeIngestionService service = service(client, producer, mapper);

        NtaRealtimeIngestionResponse response = service.publishValidUpdates(5L, 0, " ", 10);

        assertThat(response.publishedEvents()).isEmpty();
        assertThat(response.scannedStopTimeUpdates()).isEqualTo(2);
        assertThat(response.skippedUpdateCount()).isEqualTo(2);
        verify(mapper, never()).findRealtimeMatches(eq(5L), anyList(), anyList());
    }

    private NtaRealtimeIngestionService service(
            NtaGtfsRealtimeClient client,
            TripUpdateEventProducer producer,
            StopTimeMapper mapper
    ) {
        return new NtaRealtimeIngestionService(client, producer, mapper, objectMapper);
    }

    private static RealtimeStopTimeMatchRow match(
            String externalTripId,
            String externalStopId,
            int sequence,
            long tripId,
            long stopId
    ) {
        RealtimeStopTimeMatchRow row = new RealtimeStopTimeMatchRow();
        row.setExternalTripId(externalTripId);
        row.setExternalStopId(externalStopId);
        row.setStopSequence(sequence);
        row.setTripId(tripId);
        row.setStopId(stopId);
        return row;
    }

    private static String feed() {
        return """
                {
                  "header": {"timestamp": "1789058972"},
                  "entity": [
                    {"trip_update": {"trip": {"trip_id": "TRIP-1", "route_id": "ROUTE-1"},
                      "stop_time_update": [
                        {"stop_id": "STOP-1", "stop_sequence": 1, "departure": {"delay": 30}},
                        {"stop_id": "STOP-2", "stop_sequence": 2, "arrival": {"delay": 40}},
                        {"stop_id": "STOP-3", "stop_sequence": 3}
                      ]}},
                    {"trip_update": {"trip": {"trip_id": "TRIP-2", "route_id": "ROUTE-2"},
                      "stop_time_update": [{"stop_id": "STOP-9", "stop_sequence": 1,
                        "departure": {"delay": 50}}]}}
                  ]
                }
                """;
    }
}
