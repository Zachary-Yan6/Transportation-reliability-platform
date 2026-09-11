package com.zachary.transportation_reliability_platform.service.importer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.GtfsCalendarImportResponse;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendar;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.ServiceCalendarService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class GtfsCalendarImportService {

    private static final int BATCH_SIZE = 500;
    private static final DateTimeFormatter GTFS_DATE_FORMAT =
            DateTimeFormatter.BASIC_ISO_DATE;

    private final FeedVersionService feedVersionService;
    private final ServiceCalendarService serviceCalendarService;

    @Transactional
    public GtfsCalendarImportResponse importCalendars(
            MultipartFile file,
            Long feedVersionId
    ) {
        if (file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file must not be empty"
            );
        }

        try {
            return importCalendars(file.getInputStream(), feedVersionId);
        } catch (Exception exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to read uploaded GTFS archive: " + exception.getMessage()
            );
        }
    }

    /**
     * Imports calendar.txt from any GTFS archive stream. This overload keeps
     * the HTTP upload endpoint intact while allowing the scheduled downloader
     * to reuse precisely the same validation and persistence path.
     */
    @Transactional
    public GtfsCalendarImportResponse importCalendars(
            InputStream input,
            Long feedVersionId
    ) {
        FeedVersion feedVersion = feedVersionService.getById(feedVersionId);
        if (feedVersion == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Feed version not found: " + feedVersionId
            );
        }

        boolean alreadyImported = serviceCalendarService.exists(
                Wrappers.<ServiceCalendar>lambdaQuery()
                        .eq(ServiceCalendar::getFeedVersionId, feedVersionId)
        );

        if (alreadyImported) {
            throw new BusinessException(
                    ErrorCode.GTFS_COMPONENT_ALREADY_IMPORTED,
                    "calendar.txt has already been imported for feed version: "
                            + feedVersionId
            );
        }

        Path temporaryZip = null;

        try {
            temporaryZip = Files.createTempFile("gtfs-calendar-", ".zip");

            String uploadedChecksum = stageAndCalculateChecksum(input, temporaryZip);

            if (!uploadedChecksum.equalsIgnoreCase(feedVersion.getChecksum())) {
                throw new BusinessException(
                        ErrorCode.GTFS_FILE_MISMATCH,
                        "The uploaded ZIP does not match the selected feed version"
                );
            }

            List<ServiceCalendar> calendars = parseCalendars(
                    temporaryZip,
                    feedVersionId
            );

            if (!calendars.isEmpty()) {
                serviceCalendarService.saveBatch(calendars, BATCH_SIZE);
            }

            return new GtfsCalendarImportResponse(
                    feedVersionId,
                    calendars.size()
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "Failed to import calendar.txt: " + exception.getMessage());
        } finally {
            deleteTemporaryFile(temporaryZip);
        }
    }

    private String stageAndCalculateChecksum(
            InputStream input,
            Path temporaryZip
    ) throws IOException, java.security.NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");

        try (
                input;
                OutputStream output = Files.newOutputStream(
                        temporaryZip,
                        StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING
                )
        ) {
            byte[] buffer = new byte[8192];
            int bytesRead;

            while ((bytesRead = input.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
                output.write(buffer, 0, bytesRead);
            }
        }

        return HexFormat.of().formatHex(digest.digest());
    }

    private List<ServiceCalendar> parseCalendars(
            Path archive,
            Long feedVersionId
    ) throws IOException {
        List<ServiceCalendar> calendars = new ArrayList<>();

        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            ZipEntry calendarEntry = zipFile.getEntry("calendar.txt");

            if (calendarEntry == null) {
                throw new BusinessException(
                        ErrorCode.GTFS_FILE_MISMATCH,
                        "calendar.txt was not found in the GTFS ZIP"
                );
            }

            try (
                    InputStream input = zipFile.getInputStream(calendarEntry);
                    CSVParser parser = CSVFormat.DEFAULT.builder()
                            .setHeader()
                            .setSkipHeaderRecord(true)
                            .setTrim(true)
                            .build()
                            .parse(new java.io.InputStreamReader(
                                    input,
                                    StandardCharsets.UTF_8
                            ))
            ) {
                for (CSVRecord record : parser) {
                    ServiceCalendar calendar = new ServiceCalendar();

                    calendar.setFeedVersionId(feedVersionId);
                    calendar.setExternalServiceId(
                            requiredValue(record, "service_id")
                    );

                    calendar.setMonday(parseFlag(record, "monday"));
                    calendar.setTuesday(parseFlag(record, "tuesday"));
                    calendar.setWednesday(parseFlag(record, "wednesday"));
                    calendar.setThursday(parseFlag(record, "thursday"));
                    calendar.setFriday(parseFlag(record, "friday"));
                    calendar.setSaturday(parseFlag(record, "saturday"));
                    calendar.setSunday(parseFlag(record, "sunday"));

                    calendar.setStartDate(
                            parseGtfsDate(record, "start_date")
                    );
                    calendar.setEndDate(
                            parseGtfsDate(record, "end_date")
                    );

                    calendars.add(calendar);
                }
            }
        }

        return calendars;
    }

    private String requiredValue(CSVRecord record, String columnName) {
        String value = record.get(columnName);

        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "calendar.txt column is required: " + columnName
            );
        }

        return value.trim();
    }

    private boolean parseFlag(CSVRecord record, String columnName) {
        String value = requiredValue(record, columnName);

        if (!"0".equals(value) && !"1".equals(value)) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "calendar.txt column " + columnName
                            + " must be 0 or 1"
            );
        }

        return "1".equals(value);
    }

    private LocalDate parseGtfsDate(CSVRecord record, String columnName) {
        try {
            return LocalDate.parse(
                    requiredValue(record, columnName),
                    GTFS_DATE_FORMAT
            );
        } catch (DateTimeParseException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Invalid GTFS date in column: " + columnName
            );
        }
    }

    private void deleteTemporaryFile(Path temporaryZip) {
        if (temporaryZip == null) {
            return;
        }

        try {
            Files.deleteIfExists(temporaryZip);
        } catch (IOException exception) {
            // Cleanup does not affect a completed import, but is worth recording.
            log.warn("Could not remove temporary GTFS archive: {}", temporaryZip, exception);
        }
    }
}
