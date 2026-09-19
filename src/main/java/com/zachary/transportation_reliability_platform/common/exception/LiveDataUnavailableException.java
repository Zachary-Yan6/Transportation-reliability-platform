package com.zachary.transportation_reliability_platform.common.exception;

/**
 * Signals that Redis-backed live state is unavailable while durable
 * PostgreSQL-backed historical data can still be served.
 */
public class LiveDataUnavailableException extends BusinessException {

    public LiveDataUnavailableException(String message, Throwable cause) {
        super(ErrorCode.LIVE_DATA_UNAVAILABLE, message);
        initCause(cause);
    }
}
