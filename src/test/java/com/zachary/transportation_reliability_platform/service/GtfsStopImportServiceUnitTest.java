package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopImportService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies checksum protection and stop CSV parsing without a database. */
class GtfsStopImportServiceUnitTest {

    @Test
    void importsStopsWithCoordinatesAndOptionalBlankCoordinates() {
        byte[] archive = zip("stops.txt", """
                stop_id,stop_name,stop_lat,stop_lon
                S1,Central,53.3498,-6.2603
                S2,Terminal,,
                """);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        StopService stopService = mock(StopService.class);
        FeedVersion feedVersion = feedVersion(archive);
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion);
        when(stopService.count(any())).thenReturn(0L);
        when(stopService.saveBatch(anyList(), eq(500))).thenReturn(true);

        int count = new GtfsStopImportService(feedVersionService, stopService)
                .importStops(new ByteArrayInputStream(archive), 4L);

        ArgumentCaptor<List<Stop>> captor = ArgumentCaptor.forClass(List.class);
        verify(stopService).saveBatch(captor.capture(), eq(500));
        assertThat(count).isEqualTo(2);
        assertThat(captor.getValue().get(0))
                .extracting(Stop::getFeedVersionId, Stop::getExternalStopId,
                        Stop::getStopName, Stop::getLatitude, Stop::getLongitude)
                .containsExactly(4L, "S1", "Central", new java.math.BigDecimal("53.3498"),
                        new java.math.BigDecimal("-6.2603"));
        assertThat(captor.getValue().get(1).getLatitude()).isNull();
    }

    @Test
    void rejectsChecksumMismatchAndPreviouslyImportedStops() {
        byte[] archive = zip("stops.txt", "stop_id,stop_name\nS1,Central\n");
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        StopService stopService = mock(StopService.class);
        FeedVersion mismatched = new FeedVersion();
        mismatched.setChecksum("not-the-uploaded-file");
        when(feedVersionService.getRequiredById(4L)).thenReturn(mismatched);
        GtfsStopImportService service = new GtfsStopImportService(
                feedVersionService, stopService
        );

        assertThatThrownBy(() -> service.importStops(new ByteArrayInputStream(archive), 4L))
                .isInstanceOf(BusinessException.class);
        verify(stopService, never()).count(any());

        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(archive));
        when(stopService.count(any())).thenReturn(1L);
        assertThatThrownBy(() -> service.importStops(new ByteArrayInputStream(archive), 4L))
                .isInstanceOf(BusinessException.class);
        verify(stopService, never()).saveBatch(anyList(), eq(500));
    }

    @Test
    void rejectsMalformedAndUnsavableStopData() {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        StopService stopService = mock(StopService.class);
        GtfsStopImportService service = new GtfsStopImportService(
                feedVersionService, stopService
        );
        byte[] malformed = zip("stops.txt", "stop_id,stop_name,stop_lat\nS1,Central,north\n");
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(malformed));
        when(stopService.count(any())).thenReturn(0L);
        assertThatThrownBy(() -> service.importStops(new ByteArrayInputStream(malformed), 4L))
                .isInstanceOf(BusinessException.class);

        byte[] valid = zip("stops.txt", "stop_id,stop_name\nS1,Central\n");
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(valid));
        when(stopService.saveBatch(anyList(), eq(500))).thenReturn(false);
        assertThatThrownBy(() -> service.importStops(new ByteArrayInputStream(valid), 4L))
                .isInstanceOf(BusinessException.class);
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
