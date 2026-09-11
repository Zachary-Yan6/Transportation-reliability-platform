package com.zachary.transportation_reliability_platform.websocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.dto.LiveUpdateMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers normal delivery and safe removal of stale or broken browser sockets. */
class LiveUpdateWebSocketHandlerUnitTest {

    @Test
    void broadcastsToOpenSessionsAndDropsClosedOrBrokenSessions() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenReturn("{\"type\":\"vehicles\"}");
        LiveUpdateWebSocketHandler handler = new LiveUpdateWebSocketHandler(mapper);
        WebSocketSession open = mock(WebSocketSession.class);
        WebSocketSession closed = mock(WebSocketSession.class);
        WebSocketSession broken = mock(WebSocketSession.class);
        when(open.getId()).thenReturn("open");
        when(open.isOpen()).thenReturn(true);
        when(closed.getId()).thenReturn("closed");
        when(closed.isOpen()).thenReturn(false);
        when(broken.getId()).thenReturn("broken");
        when(broken.isOpen()).thenReturn(true);
        doThrow(new IOException("disconnected")).when(broken).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(open);
        handler.afterConnectionEstablished(closed);
        handler.afterConnectionEstablished(broken);

        handler.broadcast(new LiveUpdateMessage("vehicles", Instant.parse("2026-09-11T12:00:00Z")));

        verify(open).sendMessage(any(TextMessage.class));
        verify(closed, never()).sendMessage(any(TextMessage.class));
        verify(broken).sendMessage(any(TextMessage.class));

        handler.afterConnectionClosed(open, CloseStatus.NORMAL);
        handler.broadcast(new LiveUpdateMessage("vehicles", Instant.parse("2026-09-11T12:01:00Z")));
        verify(open).sendMessage(any(TextMessage.class));
    }

    @Test
    void serializationFailureIsContainedWithoutSendingToClients() throws Exception {
        ObjectMapper mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsString(any())).thenThrow(new JsonProcessingException("invalid") { });
        LiveUpdateWebSocketHandler handler = new LiveUpdateWebSocketHandler(mapper);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        handler.afterConnectionEstablished(session);

        handler.broadcast(new LiveUpdateMessage("alerts", Instant.now()));

        verify(session, never()).sendMessage(any(TextMessage.class));
    }
}
