package com.zachary.transportation_reliability_platform.security;

/** Consumes a shared request budget for one anonymised caller identity. */
public interface RequestRateLimiter {

    RateLimitDecision tryConsume(
            String policyName,
            String callerIdentity,
            ApiRateLimitProperties.Limit limit,
            int cost
    );
}
