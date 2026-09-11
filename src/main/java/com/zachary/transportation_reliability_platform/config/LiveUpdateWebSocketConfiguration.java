package com.zachary.transportation_reliability_platform.config;

import com.zachary.transportation_reliability_platform.websocket.LiveUpdateWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Registers the dashboard's single WebSocket endpoint. */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class LiveUpdateWebSocketConfiguration implements WebSocketConfigurer {

    private final LiveUpdateWebSocketHandler liveUpdateWebSocketHandler;
    private final LiveUpdateWebSocketProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(liveUpdateWebSocketHandler, "/ws/live")
                .setAllowedOriginPatterns(properties.allowedOriginPatterns().toArray(String[]::new));
    }
}
