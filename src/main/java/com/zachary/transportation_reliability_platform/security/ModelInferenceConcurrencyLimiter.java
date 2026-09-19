package com.zachary.transportation_reliability_platform.security;

/** Limits concurrent Python model processes inside one application instance. */
public interface ModelInferenceConcurrencyLimiter {

    boolean tryAcquire();

    void release();
}
