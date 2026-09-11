package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.service.importer.GtfsRouteImportService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Exercises validation, duplicate protection, CSV mapping, and persistence failure. */
class GtfsRouteImportServiceUnitTest {

    @Test
    void importsRoutesCreatesAnImportingFeedVersionAndMapsOptionalFields() {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        when(feedVersionService.findByChecksum(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            invocation.<FeedVersion>getArgument(0).setId(12L);
            return true;
        }).when(feedVersionService).save(any(FeedVersion.class));
        when(routeService.saveBatch(anyList(), eq(500))).thenReturn(true);
        GtfsRouteImportService service = new GtfsRouteImportService(
                feedVersionService, routeService
        );

        int count = service.importRoutes(
                new ByteArrayInputStream(zip("routes.txt", """
                        route_id,agency_id,route_short_name,route_long_name,route_type
                        R1,AGENCY,10,Airport - City,3
                        """)),
                "https://nta.example/feed.zip",
                LocalDate.of(2026, 9, 10)
        );

        ArgumentCaptor<FeedVersion> versionCaptor =
                ArgumentCaptor.forClass(FeedVersion.class);
        ArgumentCaptor<List<Route>> routeCaptor = ArgumentCaptor.forClass(List.class);
        verify(feedVersionService).save(versionCaptor.capture());
        verify(routeService).saveBatch(routeCaptor.capture(), eq(500));
        assertThat(count).isEqualTo(1);
        assertThat(versionCaptor.getValue())
                .extracting(FeedVersion::getSourceUri, FeedVersion::getLifecycleStatus,
                        FeedVersion::getEffectiveFrom, FeedVersion::getChecksum)
                .containsExactly("https://nta.example/feed.zip", "IMPORTING",
                        LocalDate.of(2026, 9, 10), versionCaptor.getValue().getChecksum());
        assertThat(versionCaptor.getValue().getChecksum()).hasSize(64);
        assertThat(routeCaptor.getValue()).singleElement().satisfies(route -> {
            assertThat(route.getFeedVersionId()).isEqualTo(12L);
            assertThat(route.getExternalRouteId()).isEqualTo("R1");
            assertThat(route.getAgencyId()).isEqualTo("AGENCY");
            assertThat(route.getShortName()).isEqualTo("10");
            assertThat(route.getLongName()).isEqualTo("Airport - City");
            assertThat(route.getRouteType()).isEqualTo((short) 3);
        });
    }

    @Test
    void rejectsMissingSourceAndDuplicateFeedBeforeItCanCreateRoutes() {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        GtfsRouteImportService service = new GtfsRouteImportService(
                feedVersionService, routeService
        );

        assertThatThrownBy(() -> service.importRoutes(
                new ByteArrayInputStream(zip("routes.txt", "route_id,route_type\nR1,3\n")),
                " ",
                null
        )).isInstanceOf(BusinessException.class);

        FeedVersion existing = new FeedVersion();
        existing.setId(99L);
        when(feedVersionService.findByChecksum(any())).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> service.importRoutes(
                new ByteArrayInputStream(zip("routes.txt", "route_id,route_type\nR1,3\n")),
                "https://nta.example/feed.zip",
                null
        )).isInstanceOf(BusinessException.class);
        verify(feedVersionService, never()).save(any());
        verify(routeService, never()).saveBatch(anyList(), eq(500));
    }

    @Test
    void rejectsMissingRoutesAndPersistenceFailure() {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        when(feedVersionService.findByChecksum(any())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            invocation.<FeedVersion>getArgument(0).setId(12L);
            return true;
        }).when(feedVersionService).save(any(FeedVersion.class));
        GtfsRouteImportService service = new GtfsRouteImportService(
                feedVersionService, routeService
        );

        assertThatThrownBy(() -> service.importRoutes(
                new ByteArrayInputStream(zip("stops.txt", "stop_id,stop_name\nS1,Central\n")),
                "https://nta.example/feed.zip", null
        )).isInstanceOf(BusinessException.class);

        when(routeService.saveBatch(anyList(), eq(500))).thenReturn(false);
        assertThatThrownBy(() -> service.importRoutes(
                new ByteArrayInputStream(zip("routes.txt", "route_id,route_type\nR1,3\n")),
                "https://nta.example/feed.zip", null
        )).isInstanceOf(BusinessException.class);
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
