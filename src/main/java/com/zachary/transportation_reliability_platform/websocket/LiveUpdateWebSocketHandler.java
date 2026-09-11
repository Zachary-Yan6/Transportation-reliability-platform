package com.zachary.transportation_reliability_platform.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.dto.LiveUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Keeps connected dashboard clients and publishes small change notifications. */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveUpdateWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.debug("Live-update WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    public void broadcast(LiveUpdateMessage update) {
        try {
            TextMessage message = new TextMessage(objectMapper.writeValueAsString(update));
            for (WebSocketSession session : sessions) {
                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }

                try {
                    // A session is not guaranteed to support concurrent sends.
                    synchronized (session) {
                        if (session.isOpen()) {
                            session.sendMessage(message);
                        }
                    }
                } catch (IOException exception) {
                    sessions.remove(session);
                    log.debug("Removing disconnected live-update client {}", session.getId());
                }
            }
        } catch (JsonProcessingException exception) {
            log.error("Unable to serialize a live-update message", exception);
        }
    }
}
