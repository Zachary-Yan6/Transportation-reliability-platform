package com.zachary.transportation_reliability_platform.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelInput;
import com.zachary.transportation_reliability_platform.service.TrainedDelayModelRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Creates the small adapter that invokes the offline Python model safely. */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class TrainedDelayModelRunnerConfiguration {

    private final TrainedDelayModelProperties properties;
    private final ObjectMapper objectMapper;

    @Bean
    public TrainedDelayModelRunner trainedDelayModelRunner() {
        return this::runPythonPrediction;
    }

    private Optional<BigDecimal> runPythonPrediction(
            Path modelPath,
            TrainedDelayModelInput input
    ) {
        List<String> command = new ArrayList<>(List.of(
                properties.pythonCommand(),
                properties.inferenceScript(),
                "--model", modelPath.toString(),
                "--stop-id", input.stopId().toString(),
                "--target-time", input.targetTime().toInstant().toString(),
                "--stop-sequence", input.stopSequence().toString()
        ));
        addOptionalInteger(command, "--scheduled-arrival-seconds", input.scheduledArrivalSeconds());
        addOptionalInteger(command, "--scheduled-departure-seconds", input.scheduledDepartureSeconds());

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean completed = process.waitFor(
                    properties.processTimeoutSeconds(),
                    TimeUnit.SECONDS
            );

            if (!completed) {
                process.destroyForcibly();
                log.warn("Python delay-model process exceeded its timeout");
                return Optional.empty();
            }

            String output = new String(
                    process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            if (process.exitValue() != 0) {
                log.warn("Python delay-model process failed: {}", output);
                return Optional.empty();
            }

            JsonNode result = objectMapper.readTree(output);
            if (!result.path("predictedDelaySeconds").isNumber()) {
                log.warn("Python delay-model process returned no numeric prediction");
                return Optional.empty();
            }

            return Optional.of(result.path("predictedDelaySeconds").decimalValue());
        } catch (Exception exception) {
            log.warn("Unable to run Python delay-model process", exception);
            return Optional.empty();
        }
    }

    private void addOptionalInteger(List<String> command, String option, Integer value) {
        if (value != null) {
            command.add(option);
            command.add(value.toString());
        }
    }
}
