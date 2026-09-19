package com.zachary.transportation_reliability_platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.response.ApiErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

/**
 * Applies Redis-backed budgets after JWT authentication but before controllers.
 * Normal API reads have a broad budget; Python inference and administrator
 * operations receive smaller budgets because they can consume more resources.
 */
@Component
@RequiredArgsConstructor
public class ApiRateLimitFilter extends OncePerRequestFilter {

    private static final String API_PREFIX = "/api/";
    private static final String AI_PREFIX = "/api/v1/ai/";
    private static final String ADMIN_PREFIX = "/api/v1/nta/";
    private static final String GTFS_PREFIX = "/api/v1/gtfs/";
    private static final String EVENTS_PREFIX = "/api/v1/events/";

    private final ApiRateLimitProperties properties;
    private final RequestRateLimiter requestRateLimiter;
    private final ObjectMapper objectMapper;
    // This per-instance guard is used only when Redis is unavailable. It keeps
    // passenger delay estimates usable while the model bulkhead prevents an
    // individual backend from starting unbounded Python processes.
    private final ConcurrentHashMap<String, LocalWindow> localInferenceWindows =
            new ConcurrentHashMap<>();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !request.getRequestURI().startsWith(API_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (!properties.enabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        PolicySelection selection = selectPolicy(request.getRequestURI());
        try {
            RateLimitDecision decision = requestRateLimiter.tryConsume(
                    selection.name(),
                    callerIdentity(request),
                    selection.limit(),
                    selection.cost()
            );
            if (!decision.allowed()) {
                writeError(
                        response,
                        request.getRequestURI(),
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMIT_EXCEEDED",
                        "Too many requests. Try again later.",
                        decision.retryAfterSeconds()
                );
                return;
            }
        } catch (RateLimitStoreUnavailableException exception) {
            if (selection.inference()) {
                RateLimitDecision localDecision = tryConsumeInferenceLocally(
                        selection,
                        callerIdentity(request)
                );
                if (localDecision.allowed()) {
                    filterChain.doFilter(request, response);
                    return;
                }
                writeError(
                        response,
                        request.getRequestURI(),
                        HttpStatus.TOO_MANY_REQUESTS,
                        "RATE_LIMIT_EXCEEDED",
                        "Too many requests. Try again later.",
                        localDecision.retryAfterSeconds()
                );
                return;
            }
            // Preserve normal read availability during a Redis incident, but
            // do not allow privileged administrator work without its shared
            // Redis guardrail. Passenger inference has a bounded local guard.
            if (!selection.highCost()) {
                filterChain.doFilter(request, response);
                return;
            }
            writeError(
                    response,
                    request.getRequestURI(),
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RATE_LIMIT_UNAVAILABLE",
                    "The high-cost request guard is temporarily unavailable.",
                    1
            );
            return;
        }

        filterChain.doFilter(request, response);
    }

    private PolicySelection selectPolicy(String requestUri) {
        if (isPredictionBatch(requestUri)) {
            return new PolicySelection(
                    "inference",
                properties.inference(),
                properties.inferenceBatchCost(),
                true,
                true
            );
        }
        if (isSinglePrediction(requestUri)) {
            return new PolicySelection(
                    "inference", properties.inference(), 1, true, true
            );
        }
        if (requestUri.startsWith(AI_PREFIX)
                || requestUri.startsWith(ADMIN_PREFIX)
                || requestUri.startsWith(GTFS_PREFIX)
                || requestUri.startsWith(EVENTS_PREFIX)) {
            return new PolicySelection("admin", properties.admin(), 1, true, false);
        }
        return new PolicySelection("normal", properties.normal(), 1, false, false);
    }

    private boolean isSinglePrediction(String requestUri) {
        return requestUri.matches("^/api/v1/trips/[^/]+/stops/[^/]+/delay-estimate$");
    }

    private boolean isPredictionBatch(String requestUri) {
        return requestUri.matches("^/api/v1/trips/[^/]+/delay-estimates$");
    }

    private String callerIdentity(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) {
            return "user:" + authentication.getName();
        }
        // Do not trust X-Forwarded-For unless a trusted reverse proxy has been
        // explicitly configured to validate and rewrite forwarding headers.
        return "ip:" + request.getRemoteAddr();
    }

    private RateLimitDecision tryConsumeInferenceLocally(
            PolicySelection selection,
            String callerIdentity
    ) {
        long now = System.currentTimeMillis();
        long windowMillis = Math.multiplyExact(
                selection.limit().windowSeconds(),
                1_000L
        );
        AtomicReference<RateLimitDecision> result = new AtomicReference<>();
        String key = selection.name() + ":" + callerIdentity;
        localInferenceWindows.compute(key, (ignored, current) -> {
            LocalWindow window = current == null || now - current.startedAtMillis() >= windowMillis
                    ? new LocalWindow(now, 0)
                    : current;
            if (window.consumed() + selection.cost() > selection.limit().capacity()) {
                long retryAfterSeconds = Math.max(
                        1,
                        (windowMillis - (now - window.startedAtMillis()) + 999) / 1_000
                );
                result.set(RateLimitDecision.rejected(retryAfterSeconds));
                return window;
            }
            result.set(RateLimitDecision.permit());
            return new LocalWindow(window.startedAtMillis(),
                    window.consumed() + selection.cost());
        });
        return result.get();
    }

    private void writeError(
            HttpServletResponse response,
            String path,
            HttpStatus status,
            String code,
            String message,
            long retryAfterSeconds
    ) throws IOException {
        ApiErrorResponse body = ApiErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .code(code)
                .message(message)
                .path(path)
                .fieldErrors(Map.of())
                .build();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private record PolicySelection(
            String name,
            ApiRateLimitProperties.Limit limit,
            int cost,
            boolean highCost,
            boolean inference
    ) {
    }

    private record LocalWindow(long startedAtMillis, int consumed) {
    }
}
