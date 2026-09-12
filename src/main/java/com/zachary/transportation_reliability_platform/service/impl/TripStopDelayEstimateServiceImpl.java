package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;
import com.zachary.transportation_reliability_platform.dto.LiveTripStopDelayResponse;
import com.zachary.transportation_reliability_platform.dto.TrainedDelayModelPrediction;
import com.zachary.transportation_reliability_platform.dto.TripStopDelayEstimateResponse;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.DelayPredictionService;
import com.zachary.transportation_reliability_platform.service.LiveTripStateService;
import com.zachary.transportation_reliability_platform.service.StopService;
import com.zachary.transportation_reliability_platform.service.StopTimeService;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelInput;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelService;
import com.zachary.transportation_reliability_platform.service.TripService;
import com.zachary.transportation_reliability_platform.service.TripStopDelayEstimateService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implements the application's live-data-first delay rule.
 *
 * <p>Live NTA state is useful only for "now", so it is never returned for a
 * future target time. This prevents a current delay from being presented as a
 * forecast. When no fresh live value exists, an approved model is used only
 * for stops with enough model-training data; otherwise the established
 * historical baseline remains the safe fallback.</p>
 */
@Service
@RequiredArgsConstructor
public class TripStopDelayEstimateServiceImpl
        implements TripStopDelayEstimateService {

    private static final Duration LIVE_REQUEST_WINDOW = Duration.ofMinutes(1);
    private static final Duration MAX_LIVE_OBSERVATION_AGE = Duration.ofMinutes(10);

    private final TripService tripService;
    private final StopService stopService;
    private final StopTimeService stopTimeService;
    private final LiveTripStateService liveTripStateService;
    private final TrainedDelayModelService trainedDelayModelService;
    private final DelayPredictionService delayPredictionService;

    @Override
    @Transactional(readOnly = true)
    public TripStopDelayEstimateResponse estimateDelay(
            Long tripId,
            Long stopId,
            Integer stopSequence,
            OffsetDateTime targetTime
    ) {
        Trip trip = tripService.getRequiredById(tripId);
        Stop stop = stopService.getRequiredById(stopId);
        StopTime scheduledStop = resolveScheduledStop(
                tripId,
                stopId,
                stopSequence
        );

        List<LiveTripStopDelayResponse> liveDelays = liveTripStateService
                .findByTrip(trip.getFeedVersionId(), trip.getExternalTripId());

        return estimateScheduledStop(
                trip,
                stop,
                scheduledStop,
                liveDelays,
                targetTime
        );
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripStopDelayEstimateResponse> estimateDelaysForTrip(
            Long tripId,
            OffsetDateTime targetTime
    ) {
        Trip trip = tripService.getRequiredById(tripId);
        List<StopTime> scheduledStops = stopTimeService.findByTripId(tripId);

        // A trip schedule already guarantees the referenced stop IDs are valid.
        Map<Long, Stop> stopsById = stopService.listByIds(
                        scheduledStops.stream()
                                .map(StopTime::getStopId)
                                .toList())
                .stream()
                .collect(Collectors.toMap(Stop::getId, Function.identity()));

        // Redis is read once for the complete trip, then matched in memory.
        List<LiveTripStopDelayResponse> liveDelays = liveTripStateService
                .findByTrip(trip.getFeedVersionId(), trip.getExternalTripId());

        return scheduledStops.stream()
                .map(scheduledStop -> estimateScheduledStop(
                        trip,
                        requiredScheduleStop(stopsById, scheduledStop),
                        scheduledStop,
                        liveDelays,
                        targetTime
                ))
                .toList();
    }

    private TripStopDelayEstimateResponse estimateScheduledStop(
            Trip trip,
            Stop stop,
            StopTime scheduledStop,
            List<LiveTripStopDelayResponse> liveDelays,
            OffsetDateTime targetTime
    ) {

        // Only use cached NTA data for a request about the present moment.
        Optional<LiveTripStopDelayResponse> liveDelay = findFreshLiveDelay(
                stop,
                scheduledStop,
                liveDelays,
                targetTime
        );

        if (liveDelay.isPresent()) {
            return liveResponse(
                    trip,
                    stop,
                    scheduledStop,
                    targetTime,
                    liveDelay.get()
            );
        }

        // A trained model is optional and can only replace the baseline after
        // its report approves promotion and this stop has adequate coverage.
        // An empty result intentionally continues through the existing
        // live-data-first fallback path instead of exposing a model failure.
        Optional<TrainedDelayModelPrediction> trainedPrediction =
                trainedDelayModelService.predictIfEligible(
                        trip.getRouteId(),
                        new TrainedDelayModelInput(
                                stop.getId(),
                                scheduledStop.getStopSequence(),
                                scheduledStop.getArrivalSeconds(),
                                scheduledStop.getDepartureSeconds(),
                                targetTime
                        )
                );

        if (trainedPrediction.isPresent()) {
            return trainedModelResponse(
                    trip,
                    stop,
                    scheduledStop,
                    targetTime,
                    trainedPrediction.get()
            );
        }

        // This is the previous prediction flow. It remains the fallback when
        // no suitable trained model exists for the requested stop.
        DelayPredictionResponse prediction = delayPredictionService.predictDelay(
                trip.getRouteId(),
                stop.getId(),
                targetTime
        );

        if (prediction.predictedDelaySeconds() == null) {
            return unavailableResponse(trip, stop, scheduledStop, targetTime);
        }

        return predictionResponse(trip, stop, scheduledStop, prediction);
    }

    private Stop requiredScheduleStop(
            Map<Long, Stop> stopsById,
            StopTime scheduledStop
    ) {
        Stop stop = stopsById.get(scheduledStop.getStopId());

        if (stop == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Scheduled stop " + scheduledStop.getStopId()
                            + " was not found"
            );
        }

        return stop;
    }

    private StopTime resolveScheduledStop(
            Long tripId,
            Long stopId,
            Integer requestedStopSequence
    ) {
        List<StopTime> matches = stopTimeService.findByTripId(tripId)
                .stream()
                .filter(stopTime -> stopId.equals(stopTime.getStopId()))
                .toList();

        if (matches.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Stop " + stopId + " is not part of trip " + tripId
            );
        }

        if (requestedStopSequence != null) {
            return matches.stream()
                    .filter(stopTime -> requestedStopSequence.equals(
                            stopTime.getStopSequence()))
                    .findFirst()
                    .orElseThrow(() -> new BusinessException(
                            ErrorCode.RESOURCE_NOT_FOUND,
                            "Stop sequence " + requestedStopSequence
                                    + " is not part of trip " + tripId
                    ));
        }

        if (matches.size() > 1) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "This trip visits stop " + stopId
                            + " more than once; provide stopSequence."
            );
        }

        return matches.getFirst();
    }

    private Optional<LiveTripStopDelayResponse> findFreshLiveDelay(
            Stop stop,
            StopTime scheduledStop,
            List<LiveTripStopDelayResponse> liveDelays,
            OffsetDateTime targetTime
    ) {
        Instant now = Instant.now();
        Instant requestedTime = targetTime.toInstant();

        if (Duration.between(now, requestedTime).abs()
                .compareTo(LIVE_REQUEST_WINDOW) > 0) {
            return Optional.empty();
        }

        return liveDelays.stream()
                .filter(live -> stop.getExternalStopId().equals(
                        live.externalStopId()))
                .filter(live -> scheduledStop.getStopSequence().equals(
                        live.stopSequence()))
                .filter(live -> live.observedAt() != null)
                .filter(live -> Duration.between(live.observedAt(), now)
                        .compareTo(MAX_LIVE_OBSERVATION_AGE) <= 0)
                .max(Comparator.comparing(LiveTripStopDelayResponse::observedAt));
    }

    private TripStopDelayEstimateResponse liveResponse(
            Trip trip,
            Stop stop,
            StopTime scheduledStop,
            OffsetDateTime targetTime,
            LiveTripStopDelayResponse liveDelay
    ) {
        return new TripStopDelayEstimateResponse(
                trip.getId(),
                trip.getRouteId(),
                scheduledStop.getStopId(),
                stop.getExternalStopId(),
                stop.getStopName(),
                scheduledStop.getStopSequence(),
                targetTime,
                BigDecimal.valueOf(liveDelay.delaySeconds()),
                "LIVE",
                "NTA_REALTIME_REDIS",
                "HIGH",
                liveDelay.observedAt(),
                null,
                null,
                null,
                "Current NTA GTFS-Realtime delay cached from the latest "
                        + "polling run."
        );
    }

    private TripStopDelayEstimateResponse predictionResponse(
            Trip trip,
            Stop stop,
            StopTime scheduledStop,
            DelayPredictionResponse prediction
    ) {
        return new TripStopDelayEstimateResponse(
                trip.getId(),
                trip.getRouteId(),
                scheduledStop.getStopId(),
                stop.getExternalStopId(),
                stop.getStopName(),
                scheduledStop.getStopSequence(),
                prediction.targetTime(),
                prediction.predictedDelaySeconds(),
                "PREDICTED",
                prediction.dataSource(),
                prediction.confidence(),
                null,
                prediction.matchedSampleCount(),
                prediction.p90DelaySeconds(),
                prediction.modelVersion(),
                prediction.explanation()
        );
    }

    private TripStopDelayEstimateResponse trainedModelResponse(
            Trip trip,
            Stop stop,
            StopTime scheduledStop,
            OffsetDateTime targetTime,
            TrainedDelayModelPrediction prediction
    ) {
        return new TripStopDelayEstimateResponse(
                trip.getId(),
                trip.getRouteId(),
                scheduledStop.getStopId(),
                stop.getExternalStopId(),
                stop.getStopName(),
                scheduledStop.getStopSequence(),
                targetTime,
                prediction.predictedDelaySeconds(),
                "PREDICTED",
                "TRAINED_ROUTE_MODEL",
                prediction.confidence(),
                null,
                prediction.trainingSampleCount(),
                null,
                prediction.modelVersion(),
                prediction.explanation()
        );
    }

    private TripStopDelayEstimateResponse unavailableResponse(
            Trip trip,
            Stop stop,
            StopTime scheduledStop,
            OffsetDateTime targetTime
    ) {
        return new TripStopDelayEstimateResponse(
                trip.getId(),
                trip.getRouteId(),
                scheduledStop.getStopId(),
                stop.getExternalStopId(),
                stop.getStopName(),
                scheduledStop.getStopSequence(),
                targetTime,
                null,
                "UNAVAILABLE",
                "NO_DATA",
                "NO_DATA",
                null,
                0L,
                null,
                "HISTORICAL_AVERAGE_BASELINE_V1",
                "No current NTA delay or historical observation is available "
                        + "for this scheduled stop."
        );
    }
}
