package com.zachary.transportation_reliability_platform.security;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Redis token bucket shared by every application instance.
 *
 * <p>The Lua script refills and consumes a bucket in one Redis operation, so
 * concurrent requests cannot both spend the same token. Only a SHA-256 hash of
 * the authenticated user or source IP appears in Redis keys.</p>
 */
@Component
@RequiredArgsConstructor
public class RedisRequestRateLimiter implements RequestRateLimiter {

    private static final String KEY_PREFIX = "rate-limit:v1:";
    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT =
            new DefaultRedisScript<>("""
                    local capacity = tonumber(ARGV[1])
                    local refillWindowMillis = tonumber(ARGV[2])
                    local cost = tonumber(ARGV[3])
                    local nowMillis = tonumber(ARGV[4])
                    local state = redis.call('HMGET', KEYS[1], 'tokens', 'updatedAt')
                    local tokens = tonumber(state[1])
                    local updatedAt = tonumber(state[2])
                    if tokens == nil then tokens = capacity end
                    if updatedAt == nil then updatedAt = nowMillis end
                    local elapsed = math.max(0, nowMillis - updatedAt)
                    tokens = math.min(capacity, tokens + elapsed * capacity / refillWindowMillis)
                    if tokens < cost then
                      redis.call('HSET', KEYS[1], 'tokens', tokens, 'updatedAt', nowMillis)
                      redis.call('PEXPIRE', KEYS[1], math.max(1000, math.ceil(refillWindowMillis * 2)))
                      return math.max(1, math.ceil((cost - tokens) * refillWindowMillis / capacity))
                    end
                    redis.call('HSET', KEYS[1], 'tokens', tokens - cost, 'updatedAt', nowMillis)
                    redis.call('PEXPIRE', KEYS[1], math.max(1000, math.ceil(refillWindowMillis * 2)))
                    return 0
                    """, Long.class);

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public RateLimitDecision tryConsume(
            String policyName,
            String callerIdentity,
            ApiRateLimitProperties.Limit limit,
            int cost
    ) {
        long refillWindowMillis = Math.multiplyExact(limit.windowSeconds(), 1_000L);
        try {
            Long retryAfterMillis = stringRedisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(redisKey(policyName, callerIdentity)),
                    Integer.toString(limit.capacity()),
                    Long.toString(refillWindowMillis),
                    Integer.toString(cost),
                    Long.toString(System.currentTimeMillis())
            );
            if (retryAfterMillis == null) {
                throw new RateLimitStoreUnavailableException(
                        new IllegalStateException("Redis did not return a rate-limit decision")
                );
            }
            if (retryAfterMillis == 0) {
                return RateLimitDecision.permit();
            }
            return RateLimitDecision.rejected((retryAfterMillis + 999) / 1_000);
        } catch (RateLimitStoreUnavailableException exception) {
            throw exception;
        } catch (DataAccessException exception) {
            throw new RateLimitStoreUnavailableException(exception);
        } catch (RuntimeException exception) {
            // Redis drivers can surface script/protocol failures outside the
            // DataAccessException hierarchy. Treat all such failures as an
            // unavailable guard rather than accidentally bypassing a budget.
            throw new RateLimitStoreUnavailableException(exception);
        }
    }

    private String redisKey(String policyName, String callerIdentity) {
        return KEY_PREFIX + policyName + ":" + sha256(callerIdentity);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available in the JDK", exception);
        }
    }
}
