package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.config.NtaStaticGtfsProperties;
import com.zachary.transportation_reliability_platform.dto.GtfsCalendarImportResponse;
import com.zachary.transportation_reliability_platform.dto.GtfsCalendarDateImportResponse;
import com.zachary.transportation_reliability_platform.dto.response.NtaStaticGtfsSyncResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.service.client.NtaStaticGtfsClient;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarDateImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsRouteImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopTimeImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsTripImportService;
import com.zachary.transportation_reliability_platform.service.impl.NtaStaticGtfsRefreshService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests the all-or-nothing static-feed refresh orchestration with an in-memory ZIP. */
class NtaStaticGtfsRefreshServiceUnitTest {

    @Test
    void knownChecksumSkipsEveryImporterAndLeavesTheCurrentFeedUntouched() {
        Dependencies dependencies = dependencies();
        FeedVersion existing = feedVersion(3L, "known-checksum", LocalDate.of(2026, 9, 1));
        when(dependencies.client.downloadArchive()).thenReturn(new byte[]{1, 2, 3});
        when(dependencies.feedVersionService.findByChecksum(any())).thenReturn(Optional.of(existing));

        NtaStaticGtfsSyncResponse result = dependencies.service().refreshIfChanged();

        assertThat(result.status()).isEqualTo("UNCHANGED");
        assertThat(result.feedVersionId()).isEqualTo(3L);
        verify(dependencies.routeImporter, never()).importRoutes(any(), any(), any());
        verify(dependencies.feedVersionService, never()).activate(any());
    }

    @Test
    void newArchiveImportsEveryAvailableComponentThenActivatesTheNewFeed() {
        Dependencies dependencies = dependencies();
        FeedVersion imported = feedVersion(8L, "created-by-route-import", null);
        when(dependencies.client.downloadArchive()).thenReturn(zip(Map.of(
                "feed_info.txt", "feed_start_date\n20260910\n",
                "calendar.txt", "service_id,monday\nWKD,1\n",
                "calendar_dates.txt", "service_id,date,exception_type\nWKD,20260911,1\n"
        )));
        when(dependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.of(imported));
        when(dependencies.routeImporter.importRoutes(any(), eq("https://nta.example/gtfs.zip"),
                eq(LocalDate.of(2026, 9, 10)))).thenReturn(2);
        when(dependencies.stopImporter.importStops(any(), eq(8L))).thenReturn(3);
        when(dependencies.tripImporter.importTrips(any(), eq(8L))).thenReturn(4);
        when(dependencies.stopTimeImporter.importStopTimes(any(), eq(8L))).thenReturn(5);
        when(dependencies.calendarImporter.importCalendars(any(InputStream.class), eq(8L)))
                .thenReturn(new GtfsCalendarImportResponse(8L, 6));
        when(dependencies.calendarDateImporter.importCalendarDates(
                any(InputStream.class),
                eq(8L)
        ))
                .thenReturn(new GtfsCalendarDateImportResponse(8L, 7));

        NtaStaticGtfsSyncResponse result = dependencies.service().refreshIfChanged();

        assertThat(result)
                .extracting(
                        NtaStaticGtfsSyncResponse::status,
                        NtaStaticGtfsSyncResponse::feedVersionId,
                        NtaStaticGtfsSyncResponse::effectiveFrom,
                        NtaStaticGtfsSyncResponse::routeCount,
                        NtaStaticGtfsSyncResponse::calendarDateCount
                )
                .containsExactly("IMPORTED_AND_ACTIVATED", 8L,
                        LocalDate.of(2026, 9, 10), 2, 7);
        verify(dependencies.feedVersionService).activate(8L);
    }

    @Test
    void archiveWithoutCalendarInformationIsRejectedBeforeActivation() {
        Dependencies dependencies = dependencies();
        FeedVersion imported = feedVersion(8L, "created-by-route-import", null);
        when(dependencies.client.downloadArchive()).thenReturn(zip(Map.of(
                "feed_info.txt", "feed_start_date\ninvalid-date\n"
        )));
        when(dependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.of(imported));
        when(dependencies.routeImporter.importRoutes(any(), any(), any())).thenReturn(1);
        when(dependencies.stopImporter.importStops(any(), eq(8L))).thenReturn(1);
        when(dependencies.tripImporter.importTrips(any(), eq(8L))).thenReturn(1);
        when(dependencies.stopTimeImporter.importStopTimes(any(), eq(8L))).thenReturn(1);

        assertThatThrownBy(() -> dependencies.service().refreshIfChanged())
                .isInstanceOf(BusinessException.class);
        verify(dependencies.feedVersionService, never()).activate(any());
    }

    @Test
    void importsOnlyTheCalendarComponentWhenCalendarDatesAreNotProvided() {
        Dependencies dependencies = dependencies();
        FeedVersion imported = feedVersion(9L, "created-by-route-import", null);
        when(dependencies.client.downloadArchive()).thenReturn(zip(Map.of(
                "calendar.txt", "service_id,monday\nWKD,1\n"
        )));
        when(dependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.of(imported));
        prepareRequiredImporters(dependencies, 9L);
        when(dependencies.calendarImporter.importCalendars(any(InputStream.class), eq(9L)))
                .thenReturn(new GtfsCalendarImportResponse(9L, 2));

        NtaStaticGtfsSyncResponse result = dependencies.service().refreshIfChanged();

        assertThat(result)
                .extracting(NtaStaticGtfsSyncResponse::calendarCount,
                        NtaStaticGtfsSyncResponse::calendarDateCount)
                .containsExactly(2, 0);
        verify(dependencies.calendarDateImporter, never()).importCalendarDates(
                any(InputStream.class), any(Long.class)
        );
        verify(dependencies.feedVersionService).activate(9L);
    }

    @Test
    void importsOnlyCalendarDatesAndRejectsAFeedVersionThatRouteImportDidNotCreate() {
        Dependencies calendarDateDependencies = dependencies();
        FeedVersion imported = feedVersion(10L, "created-by-route-import", null);
        when(calendarDateDependencies.client.downloadArchive()).thenReturn(zip(Map.of(
                "calendar_dates.txt", "service_id,date,exception_type\nWKD,20260911,1\n"
        )));
        when(calendarDateDependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.of(imported));
        prepareRequiredImporters(calendarDateDependencies, 10L);
        when(calendarDateDependencies.calendarDateImporter.importCalendarDates(
                any(InputStream.class), eq(10L)
        )).thenReturn(new GtfsCalendarDateImportResponse(10L, 3));

        NtaStaticGtfsSyncResponse result = calendarDateDependencies.service().refreshIfChanged();

        assertThat(result)
                .extracting(NtaStaticGtfsSyncResponse::calendarCount,
                        NtaStaticGtfsSyncResponse::calendarDateCount)
                .containsExactly(0, 3);
        verify(calendarDateDependencies.calendarImporter, never()).importCalendars(
                any(InputStream.class), any(Long.class)
        );

        Dependencies missingVersionDependencies = dependencies();
        when(missingVersionDependencies.client.downloadArchive()).thenReturn(zip(Map.of(
                "calendar.txt", "service_id,monday\nWKD,1\n"
        )));
        when(missingVersionDependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.empty());
        when(missingVersionDependencies.routeImporter.importRoutes(any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> missingVersionDependencies.service().refreshIfChanged())
                .isInstanceOf(BusinessException.class);
        verify(missingVersionDependencies.feedVersionService, never()).activate(any());
    }

    @Test
    void fallsBackForBlankFeedInfoDatesAndRejectsAnUnreadableArchive() {
        Dependencies blankDateDependencies = dependencies();
        FeedVersion imported = feedVersion(11L, "created-by-route-import", null);
        when(blankDateDependencies.client.downloadArchive()).thenReturn(zip(Map.of(
                "feed_info.txt", "feed_start_date\n   \n",
                "calendar.txt", "service_id,monday\nWKD,1\n"
        )));
        when(blankDateDependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.of(imported));
        prepareRequiredImporters(blankDateDependencies, 11L);
        when(blankDateDependencies.calendarImporter.importCalendars(any(InputStream.class), eq(11L)))
                .thenReturn(new GtfsCalendarImportResponse(11L, 1));

        NtaStaticGtfsSyncResponse result = blankDateDependencies.service().refreshIfChanged();

        assertThat(result.effectiveFrom()).isEqualTo(LocalDate.now(java.time.ZoneOffset.UTC));

        Dependencies malformedDependencies = dependencies();
        when(malformedDependencies.client.downloadArchive()).thenReturn(new byte[]{1, 2, 3});
        when(malformedDependencies.feedVersionService.findByChecksum(any()))
                .thenReturn(Optional.empty(), Optional.of(feedVersion(12L, "created", null)));
        when(malformedDependencies.routeImporter.importRoutes(any(), any(), any())).thenReturn(1);

        assertThatThrownBy(() -> malformedDependencies.service().refreshIfChanged())
                .isInstanceOf(BusinessException.class);
    }

    private static void prepareRequiredImporters(Dependencies dependencies, Long feedVersionId) {
        when(dependencies.routeImporter.importRoutes(any(), any(), any())).thenReturn(1);
        when(dependencies.stopImporter.importStops(any(), eq(feedVersionId))).thenReturn(1);
        when(dependencies.tripImporter.importTrips(any(), eq(feedVersionId))).thenReturn(1);
        when(dependencies.stopTimeImporter.importStopTimes(any(), eq(feedVersionId))).thenReturn(1);
    }

    private static Dependencies dependencies() {
        return new Dependencies(
                mock(NtaStaticGtfsClient.class),
                mock(FeedVersionService.class),
                mock(GtfsRouteImportService.class),
                mock(GtfsStopImportService.class),
                mock(GtfsTripImportService.class),
                mock(GtfsStopTimeImportService.class),
                mock(GtfsCalendarImportService.class),
                mock(GtfsCalendarDateImportService.class)
        );
    }

    private static FeedVersion feedVersion(Long id, String checksum, LocalDate effectiveFrom) {
        FeedVersion version = new FeedVersion();
        version.setId(id);
        version.setChecksum(checksum);
        version.setEffectiveFrom(effectiveFrom);
        return version;
    }

    private static byte[] zip(Map<String, String> contents) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : contents.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bytes.toByteArray();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Dependencies(
            NtaStaticGtfsClient client,
            FeedVersionService feedVersionService,
            GtfsRouteImportService routeImporter,
            GtfsStopImportService stopImporter,
            GtfsTripImportService tripImporter,
            GtfsStopTimeImportService stopTimeImporter,
            GtfsCalendarImportService calendarImporter,
            GtfsCalendarDateImportService calendarDateImporter
    ) {
        NtaStaticGtfsRefreshService service() {
            return new NtaStaticGtfsRefreshService(
                    client,
                    new NtaStaticGtfsProperties("https://nta.example/gtfs.zip", 1_000_000),
                    feedVersionService,
                    routeImporter,
                    stopImporter,
                    tripImporter,
                    stopTimeImporter,
                    calendarImporter,
                    calendarDateImporter
            );
        }
    }
}
