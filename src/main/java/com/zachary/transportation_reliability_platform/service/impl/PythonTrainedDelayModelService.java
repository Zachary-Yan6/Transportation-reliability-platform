package com.zachary.transportation_reliability_platform.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.config.TrainedDelayModelProperties;
import com.zachary.transportation_reliability_platform.dto.TrainedDelayModelPrediction;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelInput;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelRunner;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Uses a Python joblib model only after the training report explicitly approves
 * it and confirms that the requested stop has enough training examples.
 *
 * <p>Model inference is deliberately optional. Any missing artifact, rejected
 * training report, insufficient stop coverage, or process failure returns an
 * empty result so the caller can use the established live/baseline fallback.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PythonTrainedDelayModelService implements TrainedDelayModelService {

    private static final String DEFAULT_MODEL_VERSION = "SKLEARN_RANDOM_FOREST_V1";

    private final TrainedDelayModelProperties properties;
    private final ObjectMapper objectMapper;
    private final TrainedDelayModelRunner trainedDelayModelRunner;

    @Override
    public Optional<TrainedDelayModelPrediction> predictIfEligible(
            Long routeId,
            TrainedDelayModelInput input
    ) {
        if (!properties.enabled() || routeId == null || input.stopId() == null
                || input.stopSequence() == null || input.targetTime() == null) {
            // Incomplete scheduling data cannot form a valid feature vector.
            // Returning empty preserves the established fallback behaviour.
            return Optional.empty();
        }

        // path that model in
        Path routeDirectory = Path.of(
                properties.artifactsDirectory(),
                "route-" + routeId
        );
        // to find a file in specific path
        Path modelPath = routeDirectory.resolve("delay_model.joblib");
        Path reportPath = routeDirectory.resolve("training_report.json");

        // there is no model for this route
        if (!Files.isRegularFile(modelPath) || !Files.isRegularFile(reportPath)) {
            log.debug("No trained delay model artifacts exist for route {}", routeId);
            return Optional.empty();
        }


        Optional<ModelMetadata> metadata = readEligibleMetadata(reportPath, input.stopId());
        if (metadata.isEmpty()) {
            return Optional.empty();
        }

        try {
            return trainedDelayModelRunner.predict(modelPath, input)
                    .map(prediction -> toPrediction(prediction, metadata.get()));
        } catch (RuntimeException exception) {
            // Prediction must never make the customer-facing delay endpoint fail.
            log.warn("Trained delay model failed for route {}; using fallback", routeId, exception);
            return Optional.empty();
        }
    }

    private Optional<ModelMetadata> readEligibleMetadata(
            Path reportPath,
            Long stopId
    ) {
        try (Reader reader = Files.newBufferedReader(reportPath, StandardCharsets.UTF_8)) {
            JsonNode report = objectMapper.readTree(reader);
            JsonNode promotionDecision = report.path("promotionDecision");
            JsonNode deploymentEligibility = report.path("deploymentEligibility");

            if (!promotionDecision.path("eligibleForManualPromotion").asBoolean(false)) {
                log.debug("Training report {} has not approved model promotion", reportPath);
                return Optional.empty();
            }

            long reportMinimum = deploymentEligibility
                    .path("minimumStopObservations")
                    .asLong(1);
            long requiredSamples = Math.max(
                    properties.minimumStopObservations(),
                    reportMinimum
            );
            long stopSamples = deploymentEligibility
                    .path("trainedStopSampleCounts")
                    .path(String.valueOf(stopId))
                    .asLong(0);

            if (stopSamples < requiredSamples) {
                log.debug(
                        "Route model has only {} samples for stop {}; {} are required",
                        stopSamples,
                        stopId,
                        requiredSamples
                );
                return Optional.empty();
            }

            String modelVersion = report.path("modelVersion").asText(DEFAULT_MODEL_VERSION);
            return Optional.of(new ModelMetadata(stopSamples, modelVersion));
        } catch (Exception exception) {
            // A damaged report is treated as an unavailable model, not an API error.
            log.warn("Unable to read trained delay-model report {}; using fallback", reportPath, exception);
            return Optional.empty();
        }
    }

    private TrainedDelayModelPrediction toPrediction(
            BigDecimal prediction,
            ModelMetadata metadata
    ) {
        String confidence = metadata.stopSampleCount() >= 100 ? "HIGH" : "MEDIUM";
        return new TrainedDelayModelPrediction(
                prediction,
                metadata.stopSampleCount(),
                metadata.modelVersion(),
                confidence,
                "A promoted route-specific trained model was used. The selected "
                        + "stop has " + metadata.stopSampleCount()
                        + " training observations."
        );
    }

    private record ModelMetadata(long stopSampleCount, String modelVersion) {
    }
}
