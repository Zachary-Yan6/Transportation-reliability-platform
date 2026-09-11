package com.zachary.transportation_reliability_platform.common.response;

import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class ApiErrorResponse {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String code;
    private final String message;
    private final String path;
    private final Map<String, String> fieldErrors;

    /**
     * Copies client-facing validation details at the API boundary so a caller
     * cannot change a response after it has been built.
     */
    @Builder
    public ApiErrorResponse(
            Instant timestamp,
            int status,
            String error,
            String code,
            String message,
            String path,
            Map<String, String> fieldErrors
    ) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.code = code;
        this.message = message;
        this.path = path;
        this.fieldErrors = fieldErrors == null
                ? Map.of()
                : new LinkedHashMap<>(fieldErrors);
    }

    /** Returns a copy, preserving the response object's immutable state. */
    public Map<String, String> getFieldErrors() {
        return new LinkedHashMap<>(fieldErrors);
    }
}
