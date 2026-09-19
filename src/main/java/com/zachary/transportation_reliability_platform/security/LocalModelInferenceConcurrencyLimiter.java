package com.zachary.transportation_reliability_platform.security;

import com.zachary.transportation_reliability_platform.config.TrainedDelayModelProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * Per-instance bulkhead for expensive external Python inference processes.
 * Redis rate limiting protects the shared request budget; this semaphore keeps
 * one JVM from exhausting its local CPU or memory while requests are allowed.
 */
@Component
public class LocalModelInferenceConcurrencyLimiter implements ModelInferenceConcurrencyLimiter {

    private final Semaphore permits;

    public LocalModelInferenceConcurrencyLimiter(TrainedDelayModelProperties properties) {
        permits = new Semaphore(properties.maxConcurrentInferences(), true);
    }

    @Override
    public boolean tryAcquire() {
        return permits.tryAcquire();
    }

    @Override
    public void release() {
        permits.release();
    }
}
