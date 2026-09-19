package com.zachary.transportation_reliability_platform.security;

/** Signals that the shared Redis rate-limit state cannot be read or updated. */
public class RateLimitStoreUnavailableException extends RuntimeException {

    public RateLimitStoreUnavailableException(Throwable cause) {
        super(cause);
    }
}
