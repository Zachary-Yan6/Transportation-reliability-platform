package com.zachary.transportation_reliability_platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared, Redis-backed request budgets for HTTP API categories. */
@ConfigurationProperties(prefix = "app.rate-limit")
public record ApiRateLimitProperties(
        boolean enabled,
        Limit normal,
        Limit inference,
        Limit admin,
        int inferenceBatchCost
) {

    public ApiRateLimitProperties {
        normal = Limit.safe(normal, 120, 60);
        inference = Limit.safe(inference, 20, 60);
        admin = Limit.safe(admin, 10, 60);
        inferenceBatchCost = Math.max(1, Math.min(inferenceBatchCost, inference.capacity()));
    }

    /** A token-bucket capacity and its full-refill window in seconds. */
    public record Limit(int capacity, long windowSeconds) {
        private static Limit safe(Limit value, int defaultCapacity, long defaultWindowSeconds) {
            if (value == null) {
                return new Limit(defaultCapacity, defaultWindowSeconds);
            }
            return new Limit(
                    Math.max(1, value.capacity()),
                    Math.max(1, value.windowSeconds())
            );
        }
    }
}
