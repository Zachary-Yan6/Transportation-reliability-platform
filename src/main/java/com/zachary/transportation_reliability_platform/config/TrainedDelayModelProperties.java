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
        double minimumStopCoverageDays,
        int maxConcurrentInferences,
        int processTimeoutSeconds
) {
    public TrainedDelayModelProperties {
        pythonCommand = blankOrDefault(pythonCommand, "python");
        inferenceScript = blankOrDefault(inferenceScript, "ml/predict_delay.py");
        artifactsDirectory = blankOrDefault(artifactsDirectory, "ml/artifacts");
        // Keep the production contract strict even when an environment value
        // is accidentally set below the approved data-quality gate.
        minimumStopObservations = Math.max(minimumStopObservations, 1_501);
        minimumStopCoverageDays = Math.max(minimumStopCoverageDays, 7.0);
        maxConcurrentInferences = Math.max(1, maxConcurrentInferences);
        processTimeoutSeconds = processTimeoutSeconds < 1
                ? 10
                : processTimeoutSeconds;
    }

    private static String blankOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
