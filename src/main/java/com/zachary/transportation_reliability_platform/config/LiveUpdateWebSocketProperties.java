package com.zachary.transportation_reliability_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Browser origins that may establish the local live-update WebSocket. */
@ConfigurationProperties(prefix = "app.live-updates")
public record LiveUpdateWebSocketProperties(
        List<String> allowedOriginPatterns
) {
    public LiveUpdateWebSocketProperties {
        allowedOriginPatterns = allowedOriginPatterns == null
                ? List.of()
                : new ArrayList<>(allowedOriginPatterns);
    }

    @Override
    public List<String> allowedOriginPatterns() {
        return List.copyOf(allowedOriginPatterns);
    }
}
