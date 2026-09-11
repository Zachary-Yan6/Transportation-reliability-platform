package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopTimeImportService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests stop-time prerequisite checks, GTFS time parsing, and batch persistence. */
class GtfsStopTimeImportServiceUnitTest {

    @Test
    void importsMappedStopTimesAndSupportsGtfsHoursBeyondMidnight() {
        byte[] archive = zip("stop_times.txt", """
                trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type
                T1,25:01:02,,S1,1,0,1
                T1,,10:00:00,S2,2,,
                """);
        Dependencies dependencies = dependencies(archive);
        when(dependencies.stopTimeService.existsForFeedVersionId(4L)).thenReturn(false);
        when(dependencies.tripService.list(any(Wrapper.class))).thenReturn(List.of(trip("T1", 10L)));
        when(dependencies.stopService.list(any(Wrapper.class))).thenReturn(List.of(stop("S1", 20L), stop("S2", 21L)));
        when(dependencies.stopTimeService.saveBatch(anyList(), eq(1_000))).thenReturn(true);

        int count = dependencies.service().importStopTimes(
                new ByteArrayInputStream(archive), 4L
        );

        ArgumentCaptor<List<StopTime>> captor = ArgumentCaptor.forClass(List.class);
        verify(dependencies.stopTimeService).saveBatch(captor.capture(), eq(1_000));
        assertThat(count).isEqualTo(2);
        assertThat(captor.getValue())
                .extracting(StopTime::getTripId, StopTime::getStopId,
                        StopTime::getArrivalSeconds, StopTime::getDepartureSeconds,
                        StopTime::getPickupType, StopTime::getDropOffType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(10L, 20L, 90_062, null, (short) 0, (short) 1),
                        org.assertj.core.groups.Tuple.tuple(10L, 21L, null, 36_000, null, null)
                );
    }

    @Test
    void rejectsChecksumMismatchExistingDataAndMissingPrerequisites() {
        byte[] archive = zip("stop_times.txt", "trip_id,arrival_time,stop_id,stop_sequence\nT1,10:00:00,S1,1\n");
        Dependencies dependencies = dependencies(archive);
        FeedVersion mismatched = new FeedVersion();
        mismatched.setChecksum("wrong");
        when(dependencies.feedVersionService.getRequiredById(4L)).thenReturn(mismatched);

        assertThatThrownBy(() -> dependencies.service().importStopTimes(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);
        verify(dependencies.stopTimeService, never()).existsForFeedVersionId(4L);

        when(dependencies.feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(archive));
        when(dependencies.stopTimeService.existsForFeedVersionId(4L)).thenReturn(true);
        assertThatThrownBy(() -> dependencies.service().importStopTimes(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);

        when(dependencies.stopTimeService.existsForFeedVersionId(4L)).thenReturn(false);
        when(dependencies.tripService.list(any(Wrapper.class))).thenReturn(List.of());
        when(dependencies.stopService.list(any(Wrapper.class))).thenReturn(List.of());
        assertThatThrownBy(() -> dependencies.service().importStopTimes(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnknownReferencesInvalidTimesAndFailedBatches() {
        byte[] unknownTrip = zip("stop_times.txt", "trip_id,arrival_time,stop_id,stop_sequence\nT9,10:00:00,S1,1\n");
        Dependencies unknownTripDependencies = dependencies(unknownTrip);
        prerequisites(unknownTripDependencies);
        assertThatThrownBy(() -> unknownTripDependencies.service().importStopTimes(
                new ByteArrayInputStream(unknownTrip), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] invalidTime = zip("stop_times.txt", "trip_id,arrival_time,stop_id,stop_sequence\nT1,10:90:00,S1,1\n");
        Dependencies invalidTimeDependencies = dependencies(invalidTime);
        prerequisites(invalidTimeDependencies);
        assertThatThrownBy(() -> invalidTimeDependencies.service().importStopTimes(
                new ByteArrayInputStream(invalidTime), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] valid = zip("stop_times.txt", "trip_id,arrival_time,stop_id,stop_sequence\nT1,10:00:00,S1,1\n");
        Dependencies failedBatchDependencies = dependencies(valid);
        prerequisites(failedBatchDependencies);
        when(failedBatchDependencies.stopTimeService.saveBatch(anyList(), eq(1_000)))
                .thenReturn(false);
        assertThatThrownBy(() -> failedBatchDependencies.service().importStopTimes(
                new ByteArrayInputStream(valid), 4L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingEntryUnknownStopIncompleteRowsAndInvalidOptionalValues() {
        byte[] missingEntry = zip("routes.txt", "route_id,route_short_name\nR1,1\n");
        Dependencies missingEntryDependencies = dependencies(missingEntry);
        prerequisites(missingEntryDependencies);
        assertThatThrownBy(() -> missingEntryDependencies.service().importStopTimes(
                new ByteArrayInputStream(missingEntry), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] unknownStop = zip("stop_times.txt", "trip_id,arrival_time,stop_id,stop_sequence\nT1,10:00:00,S9,1\n");
        Dependencies unknownStopDependencies = dependencies(unknownStop);
        prerequisites(unknownStopDependencies);
        assertThatThrownBy(() -> unknownStopDependencies.service().importStopTimes(
                new ByteArrayInputStream(unknownStop), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] noTimes = zip("stop_times.txt", "trip_id,arrival_time,departure_time,stop_id,stop_sequence\nT1,,,S1,1\n");
        Dependencies noTimesDependencies = dependencies(noTimes);
        prerequisites(noTimesDependencies);
        assertThatThrownBy(() -> noTimesDependencies.service().importStopTimes(
                new ByteArrayInputStream(noTimes), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] invalidOptional = zip("stop_times.txt", "trip_id,arrival_time,stop_id,stop_sequence,pickup_type\nT1,10:00:00,S1,1,invalid\n");
        Dependencies invalidOptionalDependencies = dependencies(invalidOptional);
        prerequisites(invalidOptionalDependencies);
        assertThatThrownBy(() -> invalidOptionalDependencies.service().importStopTimes(
                new ByteArrayInputStream(invalidOptional), 4L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void savesAFullBatchThenSavesTheRemainingRows() {
        StringBuilder rows = new StringBuilder(
                "trip_id,arrival_time,stop_id,stop_sequence\n"
        );
        for (int sequence = 1; sequence <= 1_001; sequence++) {
            rows.append("T1,10:00:00,S1,").append(sequence).append('\n');
        }
        byte[] archive = zip("stop_times.txt", rows.toString());
        Dependencies dependencies = dependencies(archive);
        prerequisites(dependencies);
        when(dependencies.stopTimeService.saveBatch(anyList(), eq(1_000))).thenReturn(true);

        int imported = dependencies.service().importStopTimes(
                new ByteArrayInputStream(archive), 4L
        );

        assertThat(imported).isEqualTo(1_001);
        verify(dependencies.stopTimeService, times(2)).saveBatch(anyList(), eq(1_000));
    }

    private static void prerequisites(Dependencies dependencies) {
        when(dependencies.stopTimeService.existsForFeedVersionId(4L)).thenReturn(false);
        when(dependencies.tripService.list(any(Wrapper.class))).thenReturn(List.of(trip("T1", 10L)));
        when(dependencies.stopService.list(any(Wrapper.class))).thenReturn(List.of(stop("S1", 20L)));
    }

    private static Dependencies dependencies(byte[] archive) {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        TripService tripService = mock(TripService.class);
        StopService stopService = mock(StopService.class);
        StopTimeService stopTimeService = mock(StopTimeService.class);
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(archive));
        return new Dependencies(feedVersionService, tripService, stopService, stopTimeService);
    }

    private static Trip trip(String externalTripId, Long id) {
        Trip trip = new Trip();
        trip.setExternalTripId(externalTripId);
        trip.setId(id);
        return trip;
    }

    private static Stop stop(String externalStopId, Long id) {
        Stop stop = new Stop();
        stop.setExternalStopId(externalStopId);
        stop.setId(id);
        return stop;
    }

    private static FeedVersion feedVersion(byte[] content) {
        FeedVersion feedVersion = new FeedVersion();
        feedVersion.setChecksum(checksum(content));
        return feedVersion;
    }

    private static String checksum(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content)
            );
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] zip(String name, String content) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Dependencies(
            FeedVersionService feedVersionService,
            TripService tripService,
            StopService stopService,
            StopTimeService stopTimeService
    ) {
        GtfsStopTimeImportService service() {
            return new GtfsStopTimeImportService(
                    feedVersionService, tripService, stopService, stopTimeService
            );
        }
    }
}
