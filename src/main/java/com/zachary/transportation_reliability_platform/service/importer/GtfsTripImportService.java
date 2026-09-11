package com.zachary.transportation_reliability_platform.service.importer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class GtfsTripImportService {

    private final FeedVersionService feedVersionService;
    private final RouteService routeService;
    private final TripService tripService;

    @Transactional
    public int importTrips(InputStream gtfsZipInputStream, Long feedVersionId) {
        FeedVersion feedVersion = feedVersionService.getRequiredById(feedVersionId);

        byte[] zipBytes = readZipBytes(gtfsZipInputStream);
        String checksum = calculateSha256(zipBytes);

        if (!feedVersion.getChecksum().equals(checksum)) {
            throw new BusinessException(
                    ErrorCode.GTFS_FILE_MISMATCH,
                    "Uploaded GTFS ZIP does not match feed version: " + feedVersionId
            );
        }

        long existingTripCount = tripService.count(
                Wrappers.<Trip>lambdaQuery()
                        .eq(Trip::getFeedVersionId, feedVersionId)
        );

        if (existingTripCount > 0) {
            throw new BusinessException(
                    ErrorCode.GTFS_COMPONENT_ALREADY_IMPORTED,
                    "Trips have already been imported for feed version: " + feedVersionId
            );
        }

        Map<String, Long> routeIdsByExternalRouteId = routeService.list(
                Wrappers.<Route>lambdaQuery()
                        .eq(Route::getFeedVersionId, feedVersionId)
        ).stream().collect(Collectors.toMap(
                Route::getExternalRouteId,
                Route::getId
        ));

        if (routeIdsByExternalRouteId.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Routes must be imported before trips"
            );
        }

        List<Trip> trips = parseTrips(
                zipBytes,
                feedVersionId,
                routeIdsByExternalRouteId
        );

        if (trips.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "trips.txt does not contain any trips"
            );
        }

        boolean saved = tripService.saveBatch(trips, 500);

        if (!saved) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Unable to save imported trips"
            );
        }

        return trips.size();
    }

    private byte[] readZipBytes(InputStream inputStream) {
        try {
            return inputStream.readAllBytes();
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to read GTFS ZIP file"
            );
        }
    }

    private String calculateSha256(byte[] content) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(content);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private List<Trip> parseTrips(
            byte[] zipBytes,
            Long feedVersionId,
            Map<String, Long> routeIdsByExternalRouteId
    ) {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(zipBytes),
                StandardCharsets.UTF_8
        )) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equalsIgnoreCase("trips.txt")) {
                    return parseTripsCsv(
                            zipInputStream,
                            feedVersionId,
                            routeIdsByExternalRouteId
                    );
                }
            }

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file does not contain trips.txt"
            );
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to parse GTFS ZIP file"
            );
        }
    }

    private List<Trip> parseTripsCsv(
            InputStream inputStream,
            Long feedVersionId,
            Map<String, Long> routeIdsByExternalRouteId
    ) throws IOException {
        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .get();

        List<Trip> trips = new ArrayList<>();

        try (
                Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                CSVParser parser = CSVParser.parse(reader, csvFormat)
        ) {
            for (CSVRecord record : parser) {
                String externalRouteId = requiredValue(record, "route_id");
                Long routeId = routeIdsByExternalRouteId.get(externalRouteId);

                if (routeId == null) {
                    throw new BusinessException(
                            ErrorCode.VALIDATION_ERROR,
                            "Trip references an unknown route_id: " + externalRouteId
                    );
                }

                Trip trip = new Trip();
                trip.setFeedVersionId(feedVersionId);
                trip.setRouteId(routeId);
                trip.setExternalTripId(requiredValue(record, "trip_id"));
                trip.setServiceId(requiredValue(record, "service_id"));
                trip.setTripHeadsign(optionalValue(record, "trip_headsign"));
                trip.setDirectionId(parseOptionalShort(record, "direction_id"));

                trips.add(trip);
            }
        }

        return trips;
    }

    private Short parseOptionalShort(CSVRecord record, String columnName) {
        String value = optionalValue(record, columnName);

        if (value == null) {
            return null;
        }

        try {
            return Short.parseShort(value);
        } catch (NumberFormatException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Invalid " + columnName + " at CSV record " + record.getRecordNumber()
            );
        }
    }

    private String requiredValue(CSVRecord record, String columnName) {
        String value = optionalValue(record, columnName);

        if (value == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Missing " + columnName + " at CSV record " + record.getRecordNumber()
            );
        }

        return value;
    }

    private String optionalValue(CSVRecord record, String columnName) {
        if (!record.isMapped(columnName)) {
            return null;
        }

        String value = record.get(columnName);

        return value == null || value.isBlank() ? null : value.trim();
    }
}