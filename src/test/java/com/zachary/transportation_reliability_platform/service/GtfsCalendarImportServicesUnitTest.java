package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.GtfsCalendarDateImportResponse;
import com.zachary.transportation_reliability_platform.dto.GtfsCalendarImportResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendar;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendarDate;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarDateImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarImportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.LocalDate;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Uses actual temporary ZIP parsing to cover calendar and calendar-date import rules. */
class GtfsCalendarImportServicesUnitTest {

    @Test
    void importsCalendarRowsWithWeeklyFlagsAndDateRange() {
        byte[] archive = zip("calendar.txt", """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                WKD,1,1,1,1,1,0,0,20260901,20261231
                """);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        ServiceCalendarService calendarService = mock(ServiceCalendarService.class);
        when(feedVersionService.getById(4L)).thenReturn(feedVersion(archive));
        when(calendarService.exists(any())).thenReturn(false);
        when(calendarService.saveBatch(anyList(), eq(500))).thenReturn(true);

        GtfsCalendarImportResponse result = new GtfsCalendarImportService(
                feedVersionService, calendarService
        ).importCalendars(new ByteArrayInputStream(archive), 4L);

        ArgumentCaptor<List<ServiceCalendar>> captor = ArgumentCaptor.forClass(List.class);
        verify(calendarService).saveBatch(captor.capture(), eq(500));
        assertThat(result.importedCalendarCount()).isEqualTo(1);
        assertThat(captor.getValue()).singleElement().satisfies(calendar -> {
            assertThat(calendar.getFeedVersionId()).isEqualTo(4L);
            assertThat(calendar.getExternalServiceId()).isEqualTo("WKD");
            assertThat(calendar.getMonday()).isTrue();
            assertThat(calendar.getSaturday()).isFalse();
            assertThat(calendar.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
            assertThat(calendar.getEndDate()).isEqualTo(LocalDate.of(2026, 12, 31));
        });
    }

    @Test
    void calendarImportRejectsMissingFeedDuplicateComponentAndInvalidFlag() {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        ServiceCalendarService calendarService = mock(ServiceCalendarService.class);
        GtfsCalendarImportService service = new GtfsCalendarImportService(
                feedVersionService, calendarService
        );
        byte[] archive = zip("calendar.txt", """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                WKD,maybe,1,1,1,1,0,0,20260901,20261231
                """);

        assertThatThrownBy(() -> service.importCalendars(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);

        when(feedVersionService.getById(4L)).thenReturn(feedVersion(archive));
        when(calendarService.exists(any())).thenReturn(true);
        assertThatThrownBy(() -> service.importCalendars(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);
        verify(calendarService, never()).saveBatch(anyList(), eq(500));

        when(calendarService.exists(any())).thenReturn(false);
        assertThatThrownBy(() -> service.importCalendars(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void importsCalendarDateExceptionsAndKeepsTheirAddedOrRemovedMeaning() {
        byte[] archive = zip("calendar_dates.txt", """
                service_id,date,exception_type
                WKD,20260910,1
                WKD,20260911,2
                """);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        ServiceCalendarDateService dateService = mock(ServiceCalendarDateService.class);
        when(feedVersionService.getById(4L)).thenReturn(feedVersion(archive));
        when(dateService.exists(any())).thenReturn(false);
        when(dateService.saveBatch(anyList(), eq(500))).thenReturn(true);

        GtfsCalendarDateImportResponse result = new GtfsCalendarDateImportService(
                feedVersionService, dateService
        ).importCalendarDates(new ByteArrayInputStream(archive), 4L);

        ArgumentCaptor<List<ServiceCalendarDate>> captor = ArgumentCaptor.forClass(List.class);
        verify(dateService).saveBatch(captor.capture(), eq(500));
        assertThat(result.importedCalendarDateCount()).isEqualTo(2);
        assertThat(captor.getValue())
                .extracting(ServiceCalendarDate::getServiceDate,
                        ServiceCalendarDate::getExceptionType)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 10), (short) 1),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 11), (short) 2)
                );
    }

    @Test
    void calendarDateImportRejectsDuplicateAndInvalidExceptionType() {
        byte[] archive = zip("calendar_dates.txt", "service_id,date,exception_type\nWKD,20260910,9\n");
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        ServiceCalendarDateService dateService = mock(ServiceCalendarDateService.class);
        GtfsCalendarDateImportService service = new GtfsCalendarDateImportService(
                feedVersionService, dateService
        );
        when(feedVersionService.getById(4L)).thenReturn(feedVersion(archive));
        when(dateService.exists(any())).thenReturn(true);

        assertThatThrownBy(() -> service.importCalendarDates(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);
        verify(dateService, never()).saveBatch(anyList(), eq(500));

        when(dateService.exists(any())).thenReturn(false);
        assertThatThrownBy(() -> service.importCalendarDates(
                new ByteArrayInputStream(archive), 4L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void multipartUploadEntryPointsDelegateToTheValidatedImportPath() {
        byte[] calendarArchive = zip("calendar.txt", """
                service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date
                WKD,1,1,1,1,1,0,0,20260901,20261231
                """);
        FeedVersionService calendarFeedVersions = mock(FeedVersionService.class);
        ServiceCalendarService calendars = mock(ServiceCalendarService.class);
        when(calendarFeedVersions.getById(4L)).thenReturn(feedVersion(calendarArchive));
        when(calendars.exists(any())).thenReturn(false);
        when(calendars.saveBatch(anyList(), eq(500))).thenReturn(true);
        GtfsCalendarImportService calendarImporter = new GtfsCalendarImportService(
                calendarFeedVersions, calendars
        );

        GtfsCalendarImportResponse calendarResult = calendarImporter.importCalendars(
                new MockMultipartFile("file", "calendar.zip", "application/zip", calendarArchive), 4L
        );

        assertThat(calendarResult.importedCalendarCount()).isEqualTo(1);
        assertThatThrownBy(() -> calendarImporter.importCalendars(
                new MockMultipartFile("file", new byte[0]), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] dateArchive = zip("calendar_dates.txt", """
                service_id,date,exception_type
                WKD,20260910,1
                """);
        FeedVersionService dateFeedVersions = mock(FeedVersionService.class);
        ServiceCalendarDateService dates = mock(ServiceCalendarDateService.class);
        when(dateFeedVersions.getById(4L)).thenReturn(feedVersion(dateArchive));
        when(dates.exists(any())).thenReturn(false);
        when(dates.saveBatch(anyList(), eq(500))).thenReturn(true);
        GtfsCalendarDateImportService dateImporter = new GtfsCalendarDateImportService(
                dateFeedVersions, dates
        );

        GtfsCalendarDateImportResponse dateResult = dateImporter.importCalendarDates(
                new MockMultipartFile("file", "calendar-dates.zip", "application/zip", dateArchive), 4L
        );

        assertThat(dateResult.importedCalendarDateCount()).isEqualTo(1);
        assertThatThrownBy(() -> dateImporter.importCalendarDates(
                new MockMultipartFile("file", new byte[0]), 4L
        )).isInstanceOf(BusinessException.class);
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
}
