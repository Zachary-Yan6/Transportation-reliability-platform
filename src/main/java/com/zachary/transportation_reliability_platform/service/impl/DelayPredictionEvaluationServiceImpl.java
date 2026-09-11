package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.dto.DelayPredictionEvaluationResponse;
import com.zachary.transportation_reliability_platform.dto.DelayPredictionResponse;
import com.zachary.transportation_reliability_platform.dto.RouteDelayTrainingSampleResponse;
import com.zachary.transportation_reliability_platform.service.DelayPredictionEvaluationService;
import com.zachary.transportation_reliability_platform.service.DelayPredictionService;
import com.zachary.transportation_reliability_platform.service.PredictionTrainingDataService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Evaluates the online baseline without data leakage. The first 70% of the
 * chronological data is a warm-up period; each later prediction can use only
 * observations earlier than its own target timestamp.
 */
@Service
@RequiredArgsConstructor
public class DelayPredictionEvaluationServiceImpl
        implements DelayPredictionEvaluationService {

    private static final int MINIMUM_LIMIT = 30;
    private static final int MAXIMUM_LIMIT = 300;
    private static final String MODEL_VERSION =
            "HISTORICAL_AVERAGE_BASELINE_V1";

    private final RouteService routeService;
    private final PredictionTrainingDataService predictionTrainingDataService;
    private final DelayPredictionService delayPredictionService;

    @Override
    @Transactional(readOnly = true)
    public DelayPredictionEvaluationResponse evaluateRouteBaseline(
            Long routeId,
            int sampleLimit
    ) {
        routeService.getRequiredById(routeId);

        int safeLimit = Math.min(
                Math.max(sampleLimit, MINIMUM_LIMIT),
                MAXIMUM_LIMIT
        );
        List<RouteDelayTrainingSampleResponse> samples =
                predictionTrainingDataService.getRouteTrainingSamples(
                        routeId,
                        safeLimit
                );

        int warmUpCount = (int) Math.ceil(samples.size() * 0.70);
        if (warmUpCount >= samples.size()) {
            return emptyEvaluation(routeId, samples.size());
        }

        BigDecimal absoluteErrorTotal = BigDecimal.ZERO;
        BigDecimal squaredErrorTotal = BigDecimal.ZERO;
        BigDecimal actualDelayTotal = BigDecimal.ZERO;
        BigDecimal predictedDelayTotal = BigDecimal.ZERO;
        int withinFiveMinutesCount = 0;
        int evaluatedCount = 0;
        int skippedCount = 0;
        int sameWeekdayHourCount = 0;
        int stopHistoryCount = 0;
        int routeFallbackCount = 0;

        OffsetDateTime firstEvaluationSampleAt =
                samples.get(warmUpCount).getObservedAt();
        OffsetDateTime lastEvaluationSampleAt =
                samples.get(samples.size() - 1).getObservedAt();

        for (int index = warmUpCount; index < samples.size(); index++) {
            RouteDelayTrainingSampleResponse sample = samples.get(index);
            DelayPredictionResponse prediction =
                    delayPredictionService.predictDelay(
                            routeId,
                            sample.getStopId(),
                            sample.getObservedAt()
                    );

            if (prediction.predictedDelaySeconds() == null) {
                skippedCount++;
                continue;
            }

            BigDecimal actual = BigDecimal.valueOf(
                    sample.getActualDelaySeconds()
            );
            BigDecimal error = prediction.predictedDelaySeconds()
                    .subtract(actual);
            BigDecimal absoluteError = error.abs();

            absoluteErrorTotal = absoluteErrorTotal.add(absoluteError);
            squaredErrorTotal = squaredErrorTotal.add(error.multiply(error));
            actualDelayTotal = actualDelayTotal.add(actual);
            predictedDelayTotal = predictedDelayTotal.add(
                    prediction.predictedDelaySeconds()
            );
            evaluatedCount++;

            // This tells us how often the predictor had to use a broad,
            // less personalised route-level fallback.
            switch (prediction.dataSource()) {
                case "STOP_SAME_WEEKDAY_HOUR" -> sameWeekdayHourCount++;
                case "STOP_ALL_HOURS" -> stopHistoryCount++;
                case "ROUTE_ALL_STOPS" -> routeFallbackCount++;
                default -> {
                    // NO_DATA rows are skipped above; retain this case so a
                    // future prediction source does not break evaluation.
                }
            }

            if (absoluteError.compareTo(BigDecimal.valueOf(300)) <= 0) {
                withinFiveMinutesCount++;
            }
        }

        if (evaluatedCount == 0) {
            return emptyEvaluation(routeId, samples.size());
        }

        BigDecimal count = BigDecimal.valueOf(evaluatedCount);
        BigDecimal mae = absoluteErrorTotal.divide(
                count,
                2,
                RoundingMode.HALF_UP
        );
        BigDecimal mse = squaredErrorTotal.divide(
                count,
                8,
                RoundingMode.HALF_UP
        );
        BigDecimal rmse = BigDecimal.valueOf(Math.sqrt(mse.doubleValue()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal withinFiveMinutes = BigDecimal.valueOf(
                withinFiveMinutesCount
        ).multiply(BigDecimal.valueOf(100)).divide(
                count,
                2,
                RoundingMode.HALF_UP
        );

        return new DelayPredictionEvaluationResponse(
                routeId,
                samples.size() - warmUpCount,
                evaluatedCount,
                skippedCount,
                sameWeekdayHourCount,
                stopHistoryCount,
                routeFallbackCount,
                firstEvaluationSampleAt,
                lastEvaluationSampleAt,
                mae,
                rmse,
                actualDelayTotal.divide(count, 2, RoundingMode.HALF_UP),
                predictedDelayTotal.divide(count, 2, RoundingMode.HALF_UP),
                predictedDelayTotal.subtract(actualDelayTotal).divide(
                        count,
                        2,
                        RoundingMode.HALF_UP
                ),
                withinFiveMinutes,
                MODEL_VERSION,
                recommendationFor(evaluatedCount, mae)
        );
    }

    private DelayPredictionEvaluationResponse emptyEvaluation(
            Long routeId,
            int availableSampleCount
    ) {
        return new DelayPredictionEvaluationResponse(
                routeId,
                0,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                MODEL_VERSION,
                "Not enough chronological observations are available for "
                        + "backtesting. Continue collecting live data. "
                        + "Available samples: " + availableSampleCount + "."
        );
    }

    private String recommendationFor(int evaluatedCount, BigDecimal mae) {
        if (evaluatedCount < 20) {
            return "This is an early indication only. Collect more data "
                    + "before comparing models.";
        }

        if (mae.compareTo(BigDecimal.valueOf(300)) <= 0) {
            return "The historical baseline is promising. Compare future "
                    + "trained models against this MAE.";
        }

        return "The baseline is useful as a benchmark, but its error is "
                + "high. Continue collecting data, then add timetable and "
                + "calendar features to a trained model.";
    }
}
