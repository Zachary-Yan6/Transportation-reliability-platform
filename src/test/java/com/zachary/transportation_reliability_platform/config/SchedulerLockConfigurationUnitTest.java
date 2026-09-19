package com.zachary.transportation_reliability_platform.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SchedulerLockConfigurationUnitTest {

    @Test
    void configuresJdbcBackedSchedulerLocking() {
        EnableSchedulerLock scheduling = SchedulerLockConfiguration.class
                .getAnnotation(EnableSchedulerLock.class);

        assertThat(scheduling).isNotNull();
        assertThat(scheduling.defaultLockAtMostFor()).isEqualTo("PT15M");

        LockProvider provider = new SchedulerLockConfiguration()
                .schedulerLockProvider(mock(DataSource.class));
        assertThat(provider).isNotNull();
    }

    @Test
    void eachScheduledTaskHasItsOwnBoundedDistributedLock() throws Exception {
        assertLock(
                "com.zachary.transportation_reliability_platform.service.scheduler.NtaRealtimePollingJob",
                "pollNtaRealtimeFeed", "nta-realtime-trip-updates-poll", "PT5M", "PT15M"
        );
        assertLock(
                "com.zachary.transportation_reliability_platform.service.scheduler.NtaVehiclePositionPollingJob",
                "pollVehiclePositions", "nta-vehicle-positions-poll", "PT5M", "PT15M"
        );
        assertLock(
                "com.zachary.transportation_reliability_platform.service.scheduler.NtaServiceAlertPollingJob",
                "pollAlerts", "nta-service-alerts-poll", "PT5M", "PT15M"
        );
        assertLock(
                "com.zachary.transportation_reliability_platform.service.scheduler.NtaStaticGtfsPollingJob",
                "refreshStaticGtfs", "nta-static-gtfs-refresh", "PT6H", "PT7H"
        );
        assertLock(
                "com.zachary.transportation_reliability_platform.service.scheduler.RouteAlertMonitoringJob",
                "evaluateTargetRoute", "route-alert-monitoring", "PT5M", "PT30M"
        );
    }

    private void assertLock(
            String className,
            String methodName,
            String name,
            String lockAtLeastFor,
            String lockAtMostFor
    ) throws Exception {
        SchedulerLock lock = Class.forName(className)
                .getMethod(methodName)
                .getAnnotation(SchedulerLock.class);

        assertThat(lock).isNotNull();
        assertThat(lock.name()).isEqualTo(name);
        assertThat(lock.lockAtLeastFor()).isEqualTo(lockAtLeastFor);
        assertThat(lock.lockAtMostFor()).isEqualTo(lockAtMostFor);
    }
}
