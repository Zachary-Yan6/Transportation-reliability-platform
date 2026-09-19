package com.zachary.transportation_reliability_platform.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import jakarta.servlet.FilterChain;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies policy selection and standard HTTP responses at the rate-limit boundary. */
class ApiRateLimitFilterUnitTest {

    private final RequestRateLimiter requestRateLimiter = mock(RequestRateLimiter.class);
    private final ApiRateLimitFilter filter = new ApiRateLimitFilter(
            new ApiRateLimitProperties(
                    true,
                    new ApiRateLimitProperties.Limit(120, 60),
                    new ApiRateLimitProperties.Limit(20, 60),
                    new ApiRateLimitProperties.Limit(10, 60),
                    5
            ),
            requestRateLimiter,
            new ObjectMapper().findAndRegisterModules()
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void chargesBatchPredictionsAtTheHigherInferenceCost() throws Exception {
        authenticate("user@example.com");
        MockHttpServletRequest request = request(
                "/api/v1/trips/316/delay-estimates"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(requestRateLimiter.tryConsume(
                eq("inference"),
                eq("user:user@example.com"),
                any(ApiRateLimitProperties.Limit.class),
                eq(5)
        )).thenReturn(RateLimitDecision.permit());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void rejectsExhaustedSinglePredictionWithRetryAfter() throws Exception {
        authenticate("user@example.com");
        MockHttpServletRequest request = request(
                "/api/v1/trips/316/stops/80/delay-estimate"
        );
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(requestRateLimiter.tryConsume(
                eq("inference"),
                eq("user:user@example.com"),
                any(ApiRateLimitProperties.Limit.class),
                eq(1)
        )).thenReturn(RateLimitDecision.rejected(3));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("3");
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void protectsHighCostAdminApisWhenRedisIsUnavailable() throws Exception {
        MockHttpServletRequest request = request("/api/v1/ai/routes/316/training-samples.csv");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(requestRateLimiter.tryConsume(
                eq("admin"),
                any(),
                any(ApiRateLimitProperties.Limit.class),
                eq(1)
        )).thenThrow(new RateLimitStoreUnavailableException(new IllegalStateException()));

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_UNAVAILABLE");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void usesTheBoundedLocalInferenceBudgetWhenRedisIsUnavailable() throws Exception {
        MockHttpServletRequest request = request("/api/v1/trips/316/stops/80/delay-estimate");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        when(requestRateLimiter.tryConsume(
                eq("inference"), any(), any(ApiRateLimitProperties.Limit.class), eq(1)
        )).thenThrow(new RateLimitStoreUnavailableException(new IllegalStateException()));

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private MockHttpServletRequest request(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", requestUri);
        request.setRequestURI(requestUri);
        request.setRemoteAddr("203.0.113.10");
        return request;
    }

    private void authenticate(String email) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(email, null, List.of())
        );
    }
}
