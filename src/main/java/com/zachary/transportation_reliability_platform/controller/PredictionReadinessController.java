package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.RouteDelayTrainingSampleResponse;
import com.zachary.transportation_reliability_platform.dto.response.RouteTrainingDataStatusResponse;
import com.zachary.transportation_reliability_platform.service.PredictionReadinessService;
import com.zachary.transportation_reliability_platform.service.PredictionTrainingDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * API endpoints for AI prediction-data readiness.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class PredictionReadinessController {

    private final PredictionReadinessService predictionReadinessService;
    private final PredictionTrainingDataService predictionTrainingDataService;

    @GetMapping("/routes/{routeId}/training-data-status")
    public RouteTrainingDataStatusResponse getTrainingDataStatus(
            @PathVariable Long routeId
    ) {
        return predictionReadinessService.getRouteTrainingDataStatus(routeId);
    }

    /**
     * Returns labelled GTFS-Realtime samples that can be used to train or
     * evaluate a Route delay-prediction model.
     */
    @GetMapping("/routes/{routeId}/training-samples")
    public List<RouteDelayTrainingSampleResponse> getTrainingSamples(
            @PathVariable Long routeId,
            @RequestParam(defaultValue = "500") int limit
    ) {
        return predictionTrainingDataService.getRouteTrainingSamples(
                routeId,
                limit
        );
    }

    /**
     * Downloads a chronological, labelled CSV file for offline ML training.
     * The actual_delay_seconds column is the supervised-learning target.
     */
    @GetMapping(value = "/routes/{routeId}/training-samples.csv",
            produces = "text/csv")
    public ResponseEntity<String> downloadTrainingSamplesCsv(
            @PathVariable Long routeId,
            @RequestParam(defaultValue = "10000") int limit
    ) {
        String csv = predictionTrainingDataService
                .exportRouteTrainingSamplesCsv(routeId, limit);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"route-" + routeId
                                + "-delay-training-samples.csv\""
                )
                .body(csv);
    }
}
