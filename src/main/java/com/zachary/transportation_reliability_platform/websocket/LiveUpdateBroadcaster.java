package com.zachary.transportation_reliability_platform.websocket;

import com.zachary.transportation_reliability_platform.dto.LiveUpdateMessage;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Coalesces thousands of Kafka consumer writes into one browser notification
 * per data kind, avoiding a refresh storm after each NTA snapshot.
 */
@Service
@RequiredArgsConstructor
public class LiveUpdateBroadcaster {

    private static final long DEBOUNCE_MILLISECONDS = 1_500;

    private final LiveUpdateWebSocketHandler liveUpdateWebSocketHandler;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread thread = new Thread(runnable, "live-update-broadcaster");
                thread.setDaemon(true);
                return thread;
            }
    );
    private final Map<String, ScheduledFuture<?>> pendingUpdates = new ConcurrentHashMap<>();

    public void signalChange(String type) {
        ScheduledFuture<?> scheduled = scheduler.schedule(
                () -> {
                    pendingUpdates.remove(type);
                    liveUpdateWebSocketHandler.broadcast(new LiveUpdateMessage(type, Instant.now()));
                },
                DEBOUNCE_MILLISECONDS,
                TimeUnit.MILLISECONDS
        );

        ScheduledFuture<?> previous = pendingUpdates.put(type, scheduled);
        if (previous != null) {
            previous.cancel(false);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
