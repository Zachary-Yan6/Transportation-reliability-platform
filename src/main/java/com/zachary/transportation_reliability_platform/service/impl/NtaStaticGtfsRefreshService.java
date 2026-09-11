package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.config.NtaStaticGtfsProperties;
import com.zachary.transportation_reliability_platform.dto.response.NtaStaticGtfsSyncResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.client.NtaStaticGtfsClient;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarDateImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsCalendarImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsRouteImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsStopTimeImportService;
import com.zachary.transportation_reliability_platform.service.importer.GtfsTripImportService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Safely brings in a new static GTFS version. The active version only changes
 * after routes, stops, trips, stop-times, and service calendars all succeed.
 */
@Service
@RequiredArgsConstructor
public class NtaStaticGtfsRefreshService {

    private final NtaStaticGtfsClient ntaStaticGtfsClient;
    private final NtaStaticGtfsProperties properties;
    private final FeedVersionService feedVersionService;
    private final GtfsRouteImportService gtfsRouteImportService;
    private final GtfsStopImportService gtfsStopImportService;
    private final GtfsTripImportService gtfsTripImportService;
    private final GtfsStopTimeImportService gtfsStopTimeImportService;
    private final GtfsCalendarImportService gtfsCalendarImportService;
    private final GtfsCalendarDateImportService gtfsCalendarDateImportService;

    @Transactional
    public NtaStaticGtfsSyncResponse refreshIfChanged() {
        byte[] archive = ntaStaticGtfsClient.downloadArchive();
        String checksum = sha256(archive);

        FeedVersion existing = feedVersionService.findByChecksum(checksum).orElse(null);
        if (existing != null) {
            return unchanged(existing);
        }

        LocalDate effectiveFrom = readEffectiveFrom(archive);
        int routeCount = gtfsRouteImportService.importRoutes(
                new ByteArrayInputStream(archive), properties.url(), effectiveFrom);
        FeedVersion imported = feedVersionService.findByChecksum(checksum).orElseThrow(
                () -> new BusinessException(ErrorCode.INTERNAL_ERROR,
                        "GTFS feed version was not created during route import")
        );

        Long feedVersionId = imported.getId();
        int stopCount = gtfsStopImportService.importStops(
                new ByteArrayInputStream(archive), feedVersionId);
        int tripCount = gtfsTripImportService.importTrips(
                new ByteArrayInputStream(archive), feedVersionId);
        int stopTimeCount = gtfsStopTimeImportService.importStopTimes(
                new ByteArrayInputStream(archive), feedVersionId);
        boolean containsCalendar = containsEntry(archive, "calendar.txt");
        boolean containsCalendarDates = containsEntry(archive, "calendar_dates.txt");
        if (!containsCalendar && !containsCalendarDates) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The GTFS archive has neither calendar.txt nor calendar_dates.txt");
        }

        int calendarCount = containsCalendar
                ? gtfsCalendarImportService.importCalendars(
                        new ByteArrayInputStream(archive), feedVersionId).importedCalendarCount()
                : 0;
        int calendarDateCount = containsCalendarDates
                ? gtfsCalendarDateImportService.importCalendarDates(
                        new ByteArrayInputStream(archive), feedVersionId).importedCalendarDateCount()
                : 0;

        feedVersionService.activate(feedVersionId);

        return new NtaStaticGtfsSyncResponse(
                "IMPORTED_AND_ACTIVATED", feedVersionId, checksum, effectiveFrom,
                routeCount, stopCount, tripCount, stopTimeCount, calendarCount, calendarDateCount
        );
    }

    private NtaStaticGtfsSyncResponse unchanged(FeedVersion feedVersion) {
        return new NtaStaticGtfsSyncResponse(
                "UNCHANGED", feedVersion.getId(), feedVersion.getChecksum(),
                feedVersion.getEffectiveFrom(), 0, 0, 0, 0, 0, 0
        );
    }

    private LocalDate readEffectiveFrom(byte[] archive) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equalsIgnoreCase("feed_info.txt")) {
                    try (CSVParser parser = CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setTrim(true)
                            .build()
                            .parse(new InputStreamReader(zip, StandardCharsets.UTF_8))) {
                        for (CSVRecord row : parser) {
                            if (row.isMapped("feed_start_date")) {
                                String value = row.get("feed_start_date");
                                if (value != null && !value.isBlank()) {
                                    return LocalDate.parse(value.trim(), java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException | java.time.format.DateTimeParseException ignored) {
            // A feed_info date is optional in GTFS; fall back to the import date.
        }
        return LocalDate.now(ZoneOffset.UTC);
    }

    private boolean containsEntry(byte[] archive, String expectedName) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equalsIgnoreCase(expectedName)) {
                    return true;
                }
            }
            return false;
        } catch (IOException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Unable to inspect the static GTFS archive");
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
