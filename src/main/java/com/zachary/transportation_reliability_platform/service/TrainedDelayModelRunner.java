package com.zachary.transportation_reliability_platform.service;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Optional;

/** Runs a trained model process and returns its numeric prediction when valid. */
@FunctionalInterface
public interface TrainedDelayModelRunner {

    Optional<BigDecimal> predict(Path modelPath, TrainedDelayModelInput input);
}
