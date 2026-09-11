package com.zachary.transportation_reliability_platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.response.NtaFeedSummaryResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaTripUpdatePreviewResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaVehiclePositionIngestionResponse;
import com.zachary.transportation_reliability_platform.event.VehiclePositionEvent;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.impl.NtaRealtimeInspectionService;
import com.zachary.transportation_reliability_platform.service.impl.NtaVehiclePositionIngestionService;
import com.zachary.transportation_reliability_platform.service.producer.VehiclePositionEventProducer;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests NTA JSON parsing with a real Jackson mapper and mocked boundary
 * collaborators. This covers field-name variants and malformed-source paths.
 */
class NtaFeedProcessingServiceUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void inspectionSummarizesAllSupportedEntityShapesAndPreviewClampsLimit() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        when(client.fetchRawFeed()).thenReturn(realtimeFeed());
        NtaRealtimeInspectionService service = new NtaRealtimeInspectionService(
                client, objectMapper
        );

        NtaFeedSummaryResponse summary = service.inspectFeed();
        List<NtaTripUpdatePreviewResponse> preview = service.previewTripUpdates(0);

        assertThat(summary.feedTimestamp()).isEqualTo(Instant.ofEpochSecond(1_789_058_972L));
        assertThat(summary.totalEntities()).isEqualTo(3);
        assertThat(summary.tripUpdateCount()).isEqualTo(2);
        assertThat(summary.vehiclePositionCount()).isEqualTo(2);
        assertThat(summary.alertCount()).isEqualTo(1);
        assertThat(preview).hasSize(1);
        assertThat(preview.getFirst())
                .extracting(
                        NtaTripUpdatePreviewResponse::externalTripId,
                        NtaTripUpdatePreviewResponse::externalRouteId,
                        NtaTripUpdatePreviewResponse::externalStopId,
                        NtaTripUpdatePreviewResponse::stopSequence,
                        NtaTripUpdatePreviewResponse::arrivalDelaySeconds,
                        NtaTripUpdatePreviewResponse::departureDelaySeconds
                )
                .containsExactly("TRIP-1", "ROUTE-1", "STOP-1", 4, 90, null);
    }

    @Test
    void inspectionRejectsInvalidJsonForSummaryAndPreview() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        when(client.fetchRawFeed()).thenReturn("not-json");
        NtaRealtimeInspectionService service = new NtaRealtimeInspectionService(
                client, objectMapper
        );

        assertThatThrownBy(service::inspectFeed).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.previewTripUpdates(4))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void inspectionHandlesMissingTimestampsNonTripEntitiesAndOptionalPreviewFields() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        when(client.fetchRawFeed()).thenReturn("""
                {"entity":[
                  {"id":"not-a-trip"},
                  {"trip_update":{"trip":{},"stop_time_update":[{}]}}
                ]}
                """);
        NtaRealtimeInspectionService service = new NtaRealtimeInspectionService(
                client, objectMapper
        );

        assertThat(service.inspectFeed().feedTimestamp()).isNull();
        assertThat(service.previewTripUpdates(999)).singleElement().satisfies(preview -> {
            assertThat(preview.externalTripId()).isNull();
            assertThat(preview.externalStopId()).isNull();
            assertThat(preview.stopSequence()).isNull();
            assertThat(preview.arrivalDelaySeconds()).isNull();
            assertThat(preview.departureDelaySeconds()).isNull();
        });
    }

    @Test
    void vehicleIngestionPublishesValidLocationsAndSkipsInvalidOnes() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        VehiclePositionEventProducer producer = mock(VehiclePositionEventProducer.class);
        when(client.fetchRawVehicleFeed()).thenReturn(vehicleFeed());
        NtaVehiclePositionIngestionService service = new NtaVehiclePositionIngestionService(
                client, producer, objectMapper
        );

        NtaVehiclePositionIngestionResponse response = service.publishVehiclePositions(0);

        assertThat(response.feedTimestamp()).isEqualTo(Instant.ofEpochSecond(1_789_058_972L));
        assertThat(response.scannedVehicleCount()).isEqualTo(2);
        assertThat(response.publishedEventCount()).isEqualTo(1);
        assertThat(response.skippedVehicleCount()).isEqualTo(1);
        assertThat(response.publishedEvents()).singleElement()
                .extracting(
                        VehiclePositionEvent::externalVehicleId,
                        VehiclePositionEvent::externalTripId,
                        VehiclePositionEvent::externalRouteId,
                        VehiclePositionEvent::latitude,
                        VehiclePositionEvent::longitude
                )
                .containsExactly("VEHICLE-1", "TRIP-1", "ROUTE-1", 53.3, -6.2);
        verify(producer).publishBatch(response.publishedEvents());
    }

    @Test
    void vehicleIngestionValidatesManualLimitAndWrapsInvalidJson() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        VehiclePositionEventProducer producer = mock(VehiclePositionEventProducer.class);
        NtaVehiclePositionIngestionService service = new NtaVehiclePositionIngestionService(
                client, producer, objectMapper
        );

        assertThatThrownBy(() -> service.publishVehiclePositions(-1))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.publishVehiclePositions(1_001))
                .isInstanceOf(BusinessException.class);

        when(client.fetchRawVehicleFeed()).thenReturn("not-json");
        assertThatThrownBy(() -> service.publishVehiclePositions(1))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void vehicleIngestionHandlesMissingAndMalformedOptionalFieldsAndCanLimitEvents() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        VehiclePositionEventProducer producer = mock(VehiclePositionEventProducer.class);
        when(client.fetchRawVehicleFeed()).thenReturn("""
                {"entity":[
                  {"id":"not-a-vehicle"},
                  {"vehicle":null},
                  {"vehicle":{"position":{"latitude":53.3,"longitude":-6.2,"bearing":"east"},
                    "timestamp":"invalid","vehicle":{"id":"V1"}}},
                  {"vehicle":{"position":{"latitude":53.4,"longitude":-6.3},
                    "vehicle":{"id":"V2"}}}
                ]}
                """);
        NtaVehiclePositionIngestionService service = new NtaVehiclePositionIngestionService(
                client, producer, objectMapper
        );

        NtaVehiclePositionIngestionResponse response = service.publishVehiclePositions(1);

        assertThat(response.scannedVehicleCount()).isEqualTo(2);
        assertThat(response.publishedEventCount()).isEqualTo(1);
        assertThat(response.publishedEvents()).singleElement().satisfies(event -> {
            assertThat(event.externalTripId()).isNull();
            assertThat(event.directionId()).isNull();
            assertThat(event.bearing()).isNull();
        });
    }

    @Test
    void vehicleIngestionSkipsBlankIdsAndOutOfRangeCoordinates() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        VehiclePositionEventProducer producer = mock(VehiclePositionEventProducer.class);
        when(client.fetchRawVehicleFeed()).thenReturn("""
                {"header":{"timestamp":"0"},"entity":[
                  {"vehicle":{"position":{"latitude":53.3,"longitude":-6.2},"vehicle":{"id":" "}}},
                  {"vehicle":{"position":{"latitude":-91,"longitude":-6.2},"vehicle":{"id":"V2"}}},
                  {"vehicle":{"position":{"latitude":53.3,"longitude":181},"vehicle":{"id":"V3"}}}
                ]}
                """);
        NtaVehiclePositionIngestionService service = new NtaVehiclePositionIngestionService(
                client, producer, objectMapper
        );

        NtaVehiclePositionIngestionResponse response = service.publishAllVehiclePositions();

        assertThat(response.publishedEvents()).isEmpty();
        assertThat(response.skippedVehicleCount()).isEqualTo(3);
    }

    @Test
    void vehicleIngestionHandlesNullFieldsAllCoordinateBoundariesAndUnconvertibleDirection() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        VehiclePositionEventProducer producer = mock(VehiclePositionEventProducer.class);
        when(client.fetchRawVehicleFeed()).thenReturn("""
                {"header":{"timestamp":null},"entity":[
                  {"vehicle":{"position":{"latitude":53.3,"longitude":-6.2},"vehicle":{"id":null}}},
                  {"vehicle":{"position":{"longitude":-6.2},"vehicle":{"id":"V-null-lat"}}},
                  {"vehicle":{"position":{"latitude":53.3,"longitude":null},"vehicle":{"id":"V-null-lon"}}},
                  {"vehicle":{"position":{"latitude":91,"longitude":-6.2},"vehicle":{"id":"V-high-lat"}}},
                  {"vehicle":{"position":{"latitude":53.3,"longitude":-181},"vehicle":{"id":"V-low-lon"}}},
                  {"vehicle":{"trip":{"direction_id":"east"},
                    "position":{"latitude":-90,"longitude":-180},"timestamp":null,
                    "vehicle":{"id":"V-boundary"}}},
                  {"vehicle":{"trip":{"direction_id":null},
                    "position":{"latitude":90,"longitude":180},"vehicle":{"id":"V-upper-boundary"}}}
                ]}
                """);
        NtaVehiclePositionIngestionService service = new NtaVehiclePositionIngestionService(
                client, producer, objectMapper
        );

        NtaVehiclePositionIngestionResponse response = service.publishAllVehiclePositions();

        assertThat(response)
                .extracting(NtaVehiclePositionIngestionResponse::scannedVehicleCount,
                        NtaVehiclePositionIngestionResponse::publishedEventCount,
                        NtaVehiclePositionIngestionResponse::skippedVehicleCount)
                .containsExactly(7, 2, 5);
        assertThat(response.publishedEvents())
                .extracting(VehiclePositionEvent::externalVehicleId,
                        VehiclePositionEvent::directionId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("V-boundary", null),
                        org.assertj.core.groups.Tuple.tuple("V-upper-boundary", null)
                );
        verify(producer).publishBatch(response.publishedEvents());
    }

    private static String realtimeFeed() {
        return """
                {
                  "header": {"timestamp": "1789058972"},
                  "entity": [
                    {"trip_update": {"trip": {"trip_id": "TRIP-1", "route_id": "ROUTE-1"},
                      "stop_time_update": [{"stop_id": "STOP-1", "stop_sequence": 4,
                        "arrival": {"delay": 90}, "departure": {"delay": null}}]}},
                    {"tripUpdate": {}, "vehiclePosition": {}},
                    {"vehicle": {}, "alert": {}}
                  ]
                }
                """;
    }

    private static String vehicleFeed() {
        return """
                {
                  "header": {"timestamp": "1789058972"},
                  "entity": [
                    {"vehicle": {"trip": {"trip_id": "TRIP-1", "route_id": "ROUTE-1", "direction_id": 1},
                      "position": {"latitude": 53.3, "longitude": -6.2, "bearing": 180},
                      "timestamp": "1789058970", "vehicle": {"id": "VEHICLE-1"}}},
                    {"vehicle": {"position": {"latitude": 95, "longitude": -6.2},
                      "vehicle": {"id": "VEHICLE-2"}}},
                    {"id": "not-a-vehicle"}
                  ]
                }
                """;
    }
}
