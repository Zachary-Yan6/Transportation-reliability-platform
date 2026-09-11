package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;
import com.zachary.transportation_reliability_platform.dto.LiveTripStopDelayResponse;
import com.zachary.transportation_reliability_platform.dto.TripStopDelayEstimateResponse;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.impl.TripStopDelayEstimateServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests the live-data-first rule used by the trip-detail API. */
@ExtendWith(MockitoExtension.class)
class TripStopDelayEstimateServiceUnitTest {

    @Mock
    private TripService tripService;
    @Mock
    private StopService stopService;
    @Mock
    private StopTimeService stopTimeService;
    @Mock
    private LiveTripStateService liveTripStateService;
    @Mock
    private DelayPredictionService delayPredictionService;

    @Test
    void presentRequestUsesTheNewestFreshMatchingLiveDelay() {
        TripStopDelayEstimateServiceImpl service = service();
        Trip trip = trip();
        Stop stop = stop(1L, "STOP-1");
        StopTime scheduled = stopTime(1L, 1);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(tripService.getRequiredById(10L)).thenReturn(trip);
        when(stopService.getRequiredById(1L)).thenReturn(stop);
        when(stopTimeService.findByTripId(10L)).thenReturn(List.of(scheduled));
        when(liveTripStateService.findByTrip(2L, "TRIP-10")).thenReturn(List.of(
                live("STOP-1", 1, 30, Instant.now().minusSeconds(30)),
                live("STOP-1", 1, 90, Instant.now().minusSeconds(5)),
                live("OTHER", 1, 999, Instant.now())
        ));

        TripStopDelayEstimateResponse result = service.estimateDelay(10L, 1L, null, now);

        assertThat(result.state()).isEqualTo("LIVE");
        assertThat(result.estimatedDelaySeconds()).isEqualByComparingTo("90");
        assertThat(result.source()).isEqualTo("NTA_REALTIME_REDIS");
        verify(delayPredictionService, never()).predictDelay(any(), any(), any());
    }

    @Test
    void futureOrStaleLiveRequestsUseHistoricalPredictionInstead() {
        TripStopDelayEstimateServiceImpl service = service();
        Trip trip = trip();
        Stop stop = stop(1L, "STOP-1");
        StopTime scheduled = stopTime(1L, 1);
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
        when(tripService.getRequiredById(10L)).thenReturn(trip);
        when(stopService.getRequiredById(1L)).thenReturn(stop);
        when(stopTimeService.findByTripId(10L)).thenReturn(List.of(scheduled));
        when(liveTripStateService.findByTrip(2L, "TRIP-10")).thenReturn(List.of(
                live("STOP-1", 1, 30, Instant.now())
        ));
        when(delayPredictionService.predictDelay(5L, 1L, future))
                .thenReturn(prediction(5L, 1L, future, BigDecimal.valueOf(180)));

        TripStopDelayEstimateResponse result = service.estimateDelay(10L, 1L, 1, future);

        assertThat(result.state()).isEqualTo("PREDICTED");
        assertThat(result.estimatedDelaySeconds()).isEqualByComparingTo("180");
        assertThat(result.observedAt()).isNull();
    }

    @Test
    void missingPredictionProducesAnExplicitUnavailableResponse() {
        TripStopDelayEstimateServiceImpl service = service();
        Trip trip = trip();
        Stop stop = stop(1L, "STOP-1");
        StopTime scheduled = stopTime(1L, 1);
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1);
        when(tripService.getRequiredById(10L)).thenReturn(trip);
        when(stopService.getRequiredById(1L)).thenReturn(stop);
        when(stopTimeService.findByTripId(10L)).thenReturn(List.of(scheduled));
        when(liveTripStateService.findByTrip(2L, "TRIP-10")).thenReturn(List.of());
        when(delayPredictionService.predictDelay(5L, 1L, future))
                .thenReturn(prediction(5L, 1L, future, null));

        TripStopDelayEstimateResponse result = service.estimateDelay(10L, 1L, 1, future);

        assertThat(result.state()).isEqualTo("UNAVAILABLE");
        assertThat(result.source()).isEqualTo("NO_DATA");
        assertThat(result.matchedSampleCount()).isZero();
    }

    @Test
    void singleStopResolutionRejectsMissingAmbiguousAndWrongSequenceStops() {
        TripStopDelayEstimateServiceImpl service = service();
        when(tripService.getRequiredById(10L)).thenReturn(trip());
        when(stopService.getRequiredById(1L)).thenReturn(stop(1L, "STOP-1"));
        when(stopTimeService.findByTripId(10L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.estimateDelay(10L, 1L, null, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not part of trip");

        when(stopTimeService.findByTripId(10L)).thenReturn(List.of(stopTime(1L, 1), stopTime(1L, 2)));
        assertThatThrownBy(() -> service.estimateDelay(10L, 1L, null, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("more than once");
        assertThatThrownBy(() -> service.estimateDelay(10L, 1L, 3, OffsetDateTime.now(ZoneOffset.UTC)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Stop sequence 3");
    }

    @Test
    void batchEstimationReadsRedisOnceAndFailsWhenAReferencedStopIsMissing() {
        TripStopDelayEstimateServiceImpl service = service();
        Trip trip = trip();
        StopTime first = stopTime(1L, 1);
        StopTime second = stopTime(2L, 2);
        OffsetDateTime future = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        when(tripService.getRequiredById(10L)).thenReturn(trip);
        when(stopTimeService.findByTripId(10L)).thenReturn(List.of(first, second));
        when(stopService.listByIds(List.of(1L, 2L))).thenReturn(List.of(
                stop(1L, "STOP-1"), stop(2L, "STOP-2")
        ));
        when(liveTripStateService.findByTrip(2L, "TRIP-10")).thenReturn(List.of());
        when(delayPredictionService.predictDelay(eq(5L), any(), eq(future)))
                .thenAnswer(invocation -> prediction(
                        5L,
                        invocation.<Long>getArgument(1),
                        future,
                        BigDecimal.valueOf(100)
                ));

        assertThat(service.estimateDelaysForTrip(10L, future))
                .extracting(TripStopDelayEstimateResponse::state)
                .containsExactly("PREDICTED", "PREDICTED");
        verify(liveTripStateService).findByTrip(2L, "TRIP-10");

        when(stopService.listByIds(List.of(1L, 2L))).thenReturn(List.of(stop(1L, "STOP-1")));
        assertThatThrownBy(() -> service.estimateDelaysForTrip(10L, future))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Scheduled stop 2");
    }

    private TripStopDelayEstimateServiceImpl service() {
        return new TripStopDelayEstimateServiceImpl(
                tripService,
                stopService,
                stopTimeService,
                liveTripStateService,
                delayPredictionService
        );
    }

    private static Trip trip() {
        Trip trip = new Trip();
        trip.setId(10L);
        trip.setFeedVersionId(2L);
        trip.setRouteId(5L);
        trip.setExternalTripId("TRIP-10");
        return trip;
    }

    private static Stop stop(long id, String externalStopId) {
        Stop stop = new Stop();
        stop.setId(id);
        stop.setExternalStopId(externalStopId);
        stop.setStopName("Stop " + id);
        return stop;
    }

    private static StopTime stopTime(long stopId, int sequence) {
        StopTime stopTime = new StopTime();
        stopTime.setTripId(10L);
        stopTime.setStopId(stopId);
        stopTime.setStopSequence(sequence);
        return stopTime;
    }

    private static LiveTripStopDelayResponse live(
            String externalStopId,
            int sequence,
            int delay,
            Instant observedAt
    ) {
        return new LiveTripStopDelayResponse(
                UUID.randomUUID(), 2L, "TRIP-10", externalStopId,
                sequence, delay, observedAt, OffsetDateTime.now(ZoneOffset.UTC), "NTA_REALTIME"
        );
    }

    private static DelayPredictionResponse prediction(
            long routeId,
            long stopId,
            OffsetDateTime target,
            BigDecimal delay
    ) {
        return new DelayPredictionResponse(
                routeId, stopId, target, target.getDayOfWeek().getValue(),
                target.getHour(), delay, BigDecimal.valueOf(300), 20L,
                "STOP_HISTORY", "MEDIUM", "HISTORICAL_AVERAGE_BASELINE_V1", "Historical average"
        );
    }
}
