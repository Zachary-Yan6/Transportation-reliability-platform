package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.importer.GtfsTripImportService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests route-reference validation and GTFS trip parsing with an in-memory ZIP. */
class GtfsTripImportServiceUnitTest {

    @Test
    void importsTripsOnlyWhenTheyReferenceAnImportedRoute() {
        byte[] archive = zip("trips.txt", """
                route_id,service_id,trip_id,trip_headsign,direction_id
                R1,WKD,T1,Airport,1
                """);
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        TripService tripService = mock(TripService.class);
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(archive));
        when(tripService.count(any())).thenReturn(0L);
        Route route = new Route();
        route.setId(10L);
        route.setExternalRouteId("R1");
        when(routeService.list(any(Wrapper.class))).thenReturn(List.of(route));
        when(tripService.saveBatch(anyList(), eq(500))).thenReturn(true);

        int count = new GtfsTripImportService(
                feedVersionService, routeService, tripService
        ).importTrips(new ByteArrayInputStream(archive), 4L);

        ArgumentCaptor<List<Trip>> captor = ArgumentCaptor.forClass(List.class);
        verify(tripService).saveBatch(captor.capture(), eq(500));
        assertThat(count).isEqualTo(1);
        assertThat(captor.getValue()).singleElement().satisfies(trip -> {
            assertThat(trip.getFeedVersionId()).isEqualTo(4L);
            assertThat(trip.getRouteId()).isEqualTo(10L);
            assertThat(trip.getExternalTripId()).isEqualTo("T1");
            assertThat(trip.getServiceId()).isEqualTo("WKD");
            assertThat(trip.getTripHeadsign()).isEqualTo("Airport");
            assertThat(trip.getDirectionId()).isEqualTo((short) 1);
        });
    }

    @Test
    void rejectsDuplicateTripsAndMissingPrerequisiteRoutes() {
        byte[] archive = zip("trips.txt", "route_id,service_id,trip_id\nR1,WKD,T1\n");
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        TripService tripService = mock(TripService.class);
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(archive));
        when(tripService.count(any())).thenReturn(1L);
        GtfsTripImportService service = new GtfsTripImportService(
                feedVersionService, routeService, tripService
        );

        assertThatThrownBy(() -> service.importTrips(new ByteArrayInputStream(archive), 4L))
                .isInstanceOf(BusinessException.class);
        verify(routeService, never()).list(any(Wrapper.class));

        when(tripService.count(any())).thenReturn(0L);
        when(routeService.list(any(Wrapper.class))).thenReturn(List.of());
        assertThatThrownBy(() -> service.importTrips(new ByteArrayInputStream(archive), 4L))
                .isInstanceOf(BusinessException.class);
        verify(tripService, never()).saveBatch(anyList(), eq(500));
    }

    @Test
    void rejectsUnknownRouteInvalidDirectionAndPersistenceFailure() {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        TripService tripService = mock(TripService.class);
        Route route = new Route();
        route.setId(10L);
        route.setExternalRouteId("R1");
        when(routeService.list(any(Wrapper.class))).thenReturn(List.of(route));
        when(tripService.count(any())).thenReturn(0L);
        GtfsTripImportService service = new GtfsTripImportService(
                feedVersionService, routeService, tripService
        );

        byte[] unknownRoute = zip("trips.txt", "route_id,service_id,trip_id\nR2,WKD,T1\n");
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(unknownRoute));
        assertThatThrownBy(() -> service.importTrips(
                new ByteArrayInputStream(unknownRoute), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] invalidDirection = zip("trips.txt", "route_id,service_id,trip_id,direction_id\nR1,WKD,T1,east\n");
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(invalidDirection));
        assertThatThrownBy(() -> service.importTrips(
                new ByteArrayInputStream(invalidDirection), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] valid = zip("trips.txt", "route_id,service_id,trip_id\nR1,WKD,T1\n");
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(valid));
        when(tripService.saveBatch(anyList(), eq(500))).thenReturn(false);
        assertThatThrownBy(() -> service.importTrips(new ByteArrayInputStream(valid), 4L))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsArchivesWithoutTripsEmptyRowsAndMissingRequiredColumns() {
        byte[] missingTrips = zip("routes.txt", "route_id,route_short_name\nR1,1\n");
        Dependencies missingTripsDependencies = dependencies(missingTrips);
        prerequisites(missingTripsDependencies);
        assertThatThrownBy(() -> missingTripsDependencies.service().importTrips(
                new ByteArrayInputStream(missingTrips), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] noRows = zip("trips.txt", "route_id,service_id,trip_id\n");
        Dependencies noRowsDependencies = dependencies(noRows);
        prerequisites(noRowsDependencies);
        assertThatThrownBy(() -> noRowsDependencies.service().importTrips(
                new ByteArrayInputStream(noRows), 4L
        )).isInstanceOf(BusinessException.class);

        byte[] missingServiceId = zip("trips.txt", "route_id,service_id,trip_id\nR1,,T1\n");
        Dependencies missingServiceDependencies = dependencies(missingServiceId);
        prerequisites(missingServiceDependencies);
        assertThatThrownBy(() -> missingServiceDependencies.service().importTrips(
                new ByteArrayInputStream(missingServiceId), 4L
        )).isInstanceOf(BusinessException.class);
    }

    @Test
    void treatsMappedBlankOptionalFieldsAsNullAndRejectsChecksumMismatch() {
        byte[] optionalBlank = zip("trips.txt", """
                route_id,service_id,trip_id,trip_headsign,direction_id
                R1,WKD,T1,,
                """);
        Dependencies dependencies = dependencies(optionalBlank);
        prerequisites(dependencies);
        when(dependencies.tripService.saveBatch(anyList(), eq(500))).thenReturn(true);

        int imported = dependencies.service().importTrips(
                new ByteArrayInputStream(optionalBlank), 4L
        );

        assertThat(imported).isEqualTo(1);
        ArgumentCaptor<List<Trip>> captor = ArgumentCaptor.forClass(List.class);
        verify(dependencies.tripService).saveBatch(captor.capture(), eq(500));
        assertThat(captor.getValue()).singleElement().satisfies(trip -> {
            assertThat(trip.getTripHeadsign()).isNull();
            assertThat(trip.getDirectionId()).isNull();
        });

        FeedVersion mismatched = new FeedVersion();
        mismatched.setChecksum("wrong");
        when(dependencies.feedVersionService.getRequiredById(4L)).thenReturn(mismatched);
        assertThatThrownBy(() -> dependencies.service().importTrips(
                new ByteArrayInputStream(optionalBlank), 4L
        )).isInstanceOf(BusinessException.class);
    }

    private static void prerequisites(Dependencies dependencies) {
        when(dependencies.tripService.count(any())).thenReturn(0L);
        Route route = new Route();
        route.setId(10L);
        route.setExternalRouteId("R1");
        when(dependencies.routeService.list(any(Wrapper.class))).thenReturn(List.of(route));
    }

    private static Dependencies dependencies(byte[] archive) {
        FeedVersionService feedVersionService = mock(FeedVersionService.class);
        RouteService routeService = mock(RouteService.class);
        TripService tripService = mock(TripService.class);
        when(feedVersionService.getRequiredById(4L)).thenReturn(feedVersion(archive));
        return new Dependencies(feedVersionService, routeService, tripService);
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
            RouteService routeService,
            TripService tripService
    ) {
        GtfsTripImportService service() {
            return new GtfsTripImportService(feedVersionService, routeService, tripService);
        }
    }
}
