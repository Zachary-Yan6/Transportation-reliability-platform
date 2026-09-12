package com.zachary.transportation_reliability_platform.websocket;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Verifies that a superseded debounce task cannot broadcast stale updates. */
class LiveUpdateBroadcasterUnitTest {

    @Test
    void broadcastsOnlyTheLatestPendingNotificationForTheSameType() {
        LiveUpdateWebSocketHandler handler = mock(LiveUpdateWebSocketHandler.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> firstFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> secondFuture = mock(ScheduledFuture.class);
        List<Runnable> tasks = new ArrayList<>();

        when(scheduler.schedule(
                any(Runnable.class),
                eq(1_500L),
                eq(TimeUnit.MILLISECONDS)
        )).thenAnswer(invocation -> {
            tasks.add(invocation.getArgument(0));
            return tasks.size() == 1 ? firstFuture : secondFuture;
        });

        LiveUpdateBroadcaster broadcaster = new LiveUpdateBroadcaster(
                handler,
                scheduler
        );

        broadcaster.signalChange("vehicle-positions");
        broadcaster.signalChange("vehicle-positions");

        verify(firstFuture).cancel(false);

        // A task can begin just as cancellation occurs. It must observe that
        // a newer version owns the notification and remain silent.
        tasks.get(0).run();
        verifyNoInteractions(handler);

        tasks.get(1).run();
        verify(handler).broadcast(argThat(message ->
                message.type().equals("vehicle-positions")
        ));
    }
}
