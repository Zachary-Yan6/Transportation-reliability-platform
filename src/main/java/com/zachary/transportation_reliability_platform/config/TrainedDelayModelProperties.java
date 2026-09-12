package com.zachary.transportation_reliability_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for optional, route-specific Python delay models. */
@ConfigurationProperties(prefix = "app.ai.trained-model")
public record TrainedDelayModelProperties(
        boolean enabled,
        String pythonCommand,
        String inferenceScript,
        String artifactsDirectory,
        int minimumStopObservations,
        int processTimeoutSeconds
) {
    public TrainedDelayModelProperties {
        pythonCommand = blankOrDefault(pythonCommand, "python");
        inferenceScript = blankOrDefault(inferenceScript, "ml/predict_delay.py");
        artifactsDirectory = blankOrDefault(artifactsDirectory, "ml/artifacts");
        minimumStopObservations = minimumStopObservations < 1
                ? 20
                : minimumStopObservations;
        processTimeoutSeconds = processTimeoutSeconds < 1
                ? 10
                : processTimeoutSeconds;
    }

    private static String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
