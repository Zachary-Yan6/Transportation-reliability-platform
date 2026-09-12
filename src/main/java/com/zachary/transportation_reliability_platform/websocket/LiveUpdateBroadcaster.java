package com.zachary.transportation_reliability_platform.websocket;

import com.zachary.transportation_reliability_platform.dto.LiveUpdateMessage;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LiveUpdateBroadcaster applies a 1.5-second debounce to Kafka-driven state changes.
 * Instead of broadcasting a WebSocket notification for every consumed vehicle event,
 * it cancels the previous pending notification of the same type and schedules a new one.
 * This coalesces bursty updates into a single browser notification,
 * reducing unnecessary network traffic and frontend refreshes.
 */
@Service
public class LiveUpdateBroadcaster {

    private static final long DEBOUNCE_MILLISECONDS = 1_500;

    private final LiveUpdateWebSocketHandler liveUpdateWebSocketHandler;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduledFuture<?>> pendingUpdates = new ConcurrentHashMap<>();
    private final Map<String, Long> pendingVersions = new ConcurrentHashMap<>();
    private final AtomicLong notificationSequence = new AtomicLong();
    // A dedicated monitor keeps the pending update and version maps consistent.
    // Never synchronize on a java.util.concurrent collection: its implementation
    // may use its own concurrency strategy and SpotBugs correctly flags that pattern.
    private final Object pendingUpdatesMonitor = new Object();

    /**
     * Production constructor selected explicitly because the package-private
     * scheduler constructor is retained for deterministic unit tests.
     */
    @Autowired
    public LiveUpdateBroadcaster(LiveUpdateWebSocketHandler liveUpdateWebSocketHandler) {
        this(liveUpdateWebSocketHandler, Executors.newSingleThreadScheduledExecutor(
                runnable -> {
                    Thread thread = new Thread(runnable, "live-update-broadcaster");
                    thread.setDaemon(true);
                    return thread;
                }
        ));
    }

    /** Visible to tests so scheduled notifications can be deterministically checked. */
    LiveUpdateBroadcaster(
            LiveUpdateWebSocketHandler liveUpdateWebSocketHandler,
            ScheduledExecutorService scheduler
    ) {
        this.liveUpdateWebSocketHandler = liveUpdateWebSocketHandler;
        this.scheduler = scheduler;
    }

    public void signalChange(String type) {
        long version = notificationSequence.incrementAndGet();

        synchronized (pendingUpdatesMonitor) {
            ScheduledFuture<?> scheduled = scheduler.schedule(
                    () -> broadcastIfCurrent(type, version),
                    DEBOUNCE_MILLISECONDS,
                    TimeUnit.MILLISECONDS
            );

            ScheduledFuture<?> previous = pendingUpdates.put(type, scheduled);
            pendingVersions.put(type, version);
            if (previous != null) {
                previous.cancel(false);
            }
        }
    }

    private void broadcastIfCurrent(String type, long version) {
        synchronized (pendingUpdatesMonitor) {
            // Only the task that still owns this data kind may notify the
            // browser. A cancelled task that has just started must not remove
            // or broadcast over its newer replacement.
            if (!pendingVersions.remove(type, version)) {
                return;
            }
            pendingUpdates.remove(type);
        }

        liveUpdateWebSocketHandler.broadcast(new LiveUpdateMessage(type, Instant.now()));
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
