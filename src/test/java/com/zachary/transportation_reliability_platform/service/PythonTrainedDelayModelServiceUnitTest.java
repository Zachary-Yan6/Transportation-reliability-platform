package com.zachary.transportation_reliability_platform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.config.TrainedDelayModelProperties;
import com.zachary.transportation_reliability_platform.service.impl.PythonTrainedDelayModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests model eligibility decisions without starting an external Python process. */
class PythonTrainedDelayModelServiceUnitTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path temporaryDirectory;

    @Test
    void disabledModelNeverStartsInference() {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);

        var result = service(false, runner)
                .predictIfEligible(316L, input(10L));

        assertThat(result).isEmpty();
        verify(runner, never()).predict(any(), any());
    }

    @Test
    void missingArtifactsAndRejectedReportsFallBackWithoutInference() throws Exception {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);
        PythonTrainedDelayModelService service = service(true, runner);

        assertThat(service.predictIfEligible(316L, input(10L))).isEmpty();

        writeArtifacts(316L, false, 100L);
        assertThat(service.predictIfEligible(316L, input(10L))).isEmpty();
        verify(runner, never()).predict(any(), any());
    }

    @Test
    void sparseStopsFallBackEvenWhenTheRouteModelWasPromoted() throws Exception {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);
        writeArtifacts(316L, true, 19L);

        assertThat(service(true, runner).predictIfEligible(316L, input(10L))).isEmpty();
        verify(runner, never()).predict(any(), any());
    }

    @Test
    void missingReportAndUnreadableReportFallBackWithoutCallingPython() throws Exception {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);
        Path routeDirectory = Files.createDirectories(temporaryDirectory.resolve("route-316"));
        Files.createFile(routeDirectory.resolve("delay_model.joblib"));

        assertThat(service(true, runner).predictIfEligible(316L, input(10L))).isEmpty();

        Files.writeString(routeDirectory.resolve("training_report.json"), "not json");
        assertThat(service(true, runner).predictIfEligible(316L, input(10L))).isEmpty();
        verify(runner, never()).predict(any(), any());
    }

    @Test
    void promotedModelWithEnoughStopSamplesProducesPrediction() throws Exception {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);
        Path modelPath = writeArtifacts(316L, true, 42L);
        when(runner.predict(eq(modelPath), any()))
                .thenReturn(Optional.of(BigDecimal.valueOf(185.25)));

        var result = service(true, runner).predictIfEligible(316L, input(10L));

        assertThat(result).isPresent();
        assertThat(result.orElseThrow())
                .extracting(
                        prediction -> prediction.predictedDelaySeconds(),
                        prediction -> prediction.trainingSampleCount(),
                        prediction -> prediction.modelVersion(),
                        prediction -> prediction.confidence()
                )
                .containsExactly(BigDecimal.valueOf(185.25), 42L,
                        "SKLEARN_RANDOM_FOREST_V1", "MEDIUM");
    }

    @Test
    void processFailureFallsBackToTheExistingPredictionPath() throws Exception {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);
        Path modelPath = writeArtifacts(316L, true, 120L);
        when(runner.predict(eq(modelPath), any()))
                .thenThrow(new IllegalStateException("Python unavailable"));

        assertThat(service(true, runner).predictIfEligible(316L, input(10L))).isEmpty();
    }

    @Test
    void emptyRunnerResultFallsBackAndLargeStopCoverageIsHighConfidence() throws Exception {
        TrainedDelayModelRunner runner = mock(TrainedDelayModelRunner.class);
        Path modelPath = writeArtifacts(316L, true, 120L);
        when(runner.predict(eq(modelPath), any()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(BigDecimal.TEN));

        assertThat(service(true, runner).predictIfEligible(316L, input(10L))).isEmpty();
        assertThat(service(true, runner).predictIfEligible(316L, input(10L))
                .orElseThrow().confidence()).isEqualTo("HIGH");
    }

    private PythonTrainedDelayModelService service(
            boolean enabled,
            TrainedDelayModelRunner runner
    ) {
        return new PythonTrainedDelayModelService(
                new TrainedDelayModelProperties(
                        enabled,
                        "python",
                        "ml/predict_delay.py",
                        temporaryDirectory.toString(),
                        20,
                        10
                ),
                objectMapper,
                runner
        );
    }

    private Path writeArtifacts(
            long routeId,
            boolean promoted,
            long stopSamples
    ) throws Exception {
        Path routeDirectory = Files.createDirectories(
                temporaryDirectory.resolve("route-" + routeId)
        );
        Path modelPath = Files.createFile(routeDirectory.resolve("delay_model.joblib"));
        String report = """
                {
                  "modelVersion": "SKLEARN_RANDOM_FOREST_V1",
                  "promotionDecision": {
                    "eligibleForManualPromotion": %s
                  },
                  "deploymentEligibility": {
                    "minimumStopObservations": 20,
                    "trainedStopSampleCounts": {
                      "10": %d
                    }
                  }
                }
                """.formatted(promoted, stopSamples);
        Files.writeString(routeDirectory.resolve("training_report.json"), report);
        return modelPath;
    }

    private TrainedDelayModelInput input(long stopId) {
        return new TrainedDelayModelInput(
                stopId,
                3,
                28_800,
                28_860,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(30)
        );
    }
}
