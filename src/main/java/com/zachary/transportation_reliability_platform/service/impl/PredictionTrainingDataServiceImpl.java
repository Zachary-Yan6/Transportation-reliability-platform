package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.RouteDelayTrainingSampleResponse;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.PredictionTrainingDataService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Joins live delay observations to the static GTFS timetable so every row is
 * usable as a supervised-learning sample.
 */
@Service
@RequiredArgsConstructor
public class PredictionTrainingDataServiceImpl
        implements PredictionTrainingDataService {

    private static final int MAXIMUM_JSON_SAMPLE_LIMIT = 1_000;
    private static final int MAXIMUM_CSV_SAMPLE_LIMIT = 10_000;

    private final RouteService routeService;
    private final TripStopDelayObservationMapper delayObservationMapper;

    @Override
    @Transactional(readOnly = true)
    public List<RouteDelayTrainingSampleResponse> getRouteTrainingSamples(
            Long routeId,
            int limit
    ) {
        // Limit the API response, but allow enough samples for local analysis.
        return findSamples(routeId, limit, MAXIMUM_JSON_SAMPLE_LIMIT);
    }

    @Override
    @Transactional(readOnly = true)
    public String exportRouteTrainingSamplesCsv(Long routeId, int limit) {
        // CSV is intended for model training, so it permits a larger but
        // still bounded download than the JSON inspection endpoint.
        List<RouteDelayTrainingSampleResponse> samples = findSamples(
                routeId,
                limit,
                MAXIMUM_CSV_SAMPLE_LIMIT
        );

        StringBuilder csv = new StringBuilder();
        csv.append("event_id,route_id,trip_id,external_trip_id,")
                .append("stop_id,external_stop_id,stop_sequence,")
                .append("scheduled_arrival_seconds,")
                .append("scheduled_departure_seconds,")
                .append("observed_day_of_week,observed_hour,")
                .append("observed_at,actual_delay_seconds\r\n");

        for (RouteDelayTrainingSampleResponse sample : samples) {
            appendRow(csv, sample);
        }

        return csv.toString();
    }

    private List<RouteDelayTrainingSampleResponse> findSamples(
            Long routeId,
            int requestedLimit,
            int maximumLimit
    ) {
        // Return 404 instead of an empty dataset for an invalid route ID.
        routeService.getRequiredById(routeId);

        int safeLimit = Math.min(Math.max(requestedLimit, 1), maximumLimit);

        return delayObservationMapper.findTrainingSamplesByRouteId(
                routeId,
                safeLimit
        );
    }

    /**
     * Appends one RFC 4180-compatible CSV row. Text fields are escaped so an
     * external GTFS identifier containing a comma or quote remains intact.
     */
    private void appendRow(
            StringBuilder csv,
            RouteDelayTrainingSampleResponse sample
    ) {
        appendCell(csv, sample.getEventId());
        appendCell(csv, sample.getRouteId());
        appendCell(csv, sample.getTripId());
        appendCell(csv, sample.getExternalTripId());
        appendCell(csv, sample.getStopId());
        appendCell(csv, sample.getExternalStopId());
        appendCell(csv, sample.getStopSequence());
        appendCell(csv, sample.getScheduledArrivalSeconds());
        appendCell(csv, sample.getScheduledDepartureSeconds());
        appendCell(csv, sample.getObservedDayOfWeek());
        appendCell(csv, sample.getObservedHour());
        appendCell(csv, sample.getObservedAt());
        appendLastCell(csv, sample.getActualDelaySeconds());
    }

    private void appendCell(StringBuilder csv, Object value) {
        appendEscapedValue(csv, value);
        csv.append(',');
    }

    private void appendLastCell(StringBuilder csv, Object value) {
        appendEscapedValue(csv, value);
        csv.append("\r\n");
    }

    private void appendEscapedValue(StringBuilder csv, Object value) {
        if (value == null) {
            return;
        }

        String text = value.toString();
        boolean needsQuotes = text.contains(",")
                || text.contains("\"")
                || text.contains("\r")
                || text.contains("\n");

        if (!needsQuotes) {
            csv.append(text);
            return;
        }

        csv.append('"')
                .append(text.replace("\"", "\"\""))
                .append('"');
    }
}
