package com.zachary.transportation_reliability_platform.service.importer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.StopService;
import com.zachary.transportation_reliability_platform.service.StopTimeService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
public class GtfsStopTimeImportService {

    private static final int BATCH_SIZE = 1_000;

    private final FeedVersionService feedVersionService;
    private final TripService tripService;
    private final StopService stopService;
    private final StopTimeService stopTimeService;

    @Transactional
    public int importStopTimes(
            InputStream gtfsZipInputStream,
            Long feedVersionId
    ) {
        FeedVersion feedVersion = feedVersionService.getRequiredById(feedVersionId);
        StagedGtfsArchive archive = stageArchive(gtfsZipInputStream);

        try {
            if (!feedVersion.getChecksum().equals(archive.checksum())) {
                throw new BusinessException(
                        ErrorCode.GTFS_FILE_MISMATCH,
                        "Uploaded GTFS ZIP does not match feed version: " + feedVersionId
                );
            }

            if (stopTimeService.existsForFeedVersionId(feedVersionId)) {
                throw new BusinessException(
                        ErrorCode.GTFS_COMPONENT_ALREADY_IMPORTED,
                        "Stop times have already been imported for feed version: " + feedVersionId
                );
            }

            Map<String, Long> tripIds = tripService.list(
                    Wrappers.<Trip>lambdaQuery()
                            .eq(Trip::getFeedVersionId, feedVersionId)
            ).stream().collect(Collectors.toMap(
                    Trip::getExternalTripId,
                    Trip::getId
            ));

            Map<String, Long> stopIds = stopService.list(
                    Wrappers.<Stop>lambdaQuery()
                            .eq(Stop::getFeedVersionId, feedVersionId)
            ).stream().collect(Collectors.toMap(
                    Stop::getExternalStopId,
                    Stop::getId
            ));

            if (tripIds.isEmpty() || stopIds.isEmpty()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "Trips and stops must be imported before stop times"
                );
            }

            return parseAndSaveStopTimes(
                    archive.path(),
                    tripIds,
                    stopIds
            );
        } finally {
            deleteTemporaryFile(archive.path());
        }
    }

    private StagedGtfsArchive stageArchive(InputStream inputStream) {
        try {
            Path temporaryZip = Files.createTempFile("gtfs-", ".zip");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            try (
                    InputStream source = inputStream;
                    var target = Files.newOutputStream(
                            temporaryZip,
                            StandardOpenOption.TRUNCATE_EXISTING
                    )
            ) {
                byte[] buffer = new byte[8_192];
                int bytesRead;

                while ((bytesRead = source.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                    target.write(buffer, 0, bytesRead);
                }
            }

            return new StagedGtfsArchive(
                    temporaryZip,
                    HexFormat.of().formatHex(digest.digest())
            );
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to stage GTFS ZIP file"
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private int parseAndSaveStopTimes(
            Path zipPath,
            Map<String, Long> tripIds,
            Map<String, Long> stopIds
    ) {
        try (ZipFile zipFile = new ZipFile(zipPath.toFile(), StandardCharsets.UTF_8)) {
            ZipEntry entry = zipFile.getEntry("stop_times.txt");

            if (entry == null) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "GTFS ZIP file does not contain stop_times.txt"
                );
            }

            CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setTrim(true)
                    .get();

            int importedCount = 0;
            List<StopTime> batch = new ArrayList<>(BATCH_SIZE);

            try (
                    Reader reader = new InputStreamReader(
                            zipFile.getInputStream(entry),
                            StandardCharsets.UTF_8
                    );
                    CSVParser parser = CSVParser.parse(reader, csvFormat)
            ) {
                for (CSVRecord record : parser) {
                    batch.add(toStopTime(record, tripIds, stopIds));

                    if (batch.size() == BATCH_SIZE) {
                        saveBatch(batch);
                        importedCount += batch.size();
                        batch.clear();
                    }
                }
            }

            if (!batch.isEmpty()) {
                saveBatch(batch);
                importedCount += batch.size();
            }

            return importedCount;
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to parse stop_times.txt"
            );
        }
    }

    private StopTime toStopTime(
            CSVRecord record,
            Map<String, Long> tripIds,
            Map<String, Long> stopIds
    ) {
        String externalTripId = requiredValue(record, "trip_id");
        String externalStopId = requiredValue(record, "stop_id");

        Long tripId = tripIds.get(externalTripId);
        Long stopId = stopIds.get(externalStopId);

        if (tripId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unknown trip_id: " + externalTripId
            );
        }

        if (stopId == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unknown stop_id: " + externalStopId
            );
        }

        Integer arrivalSeconds = parseGtfsTime(record, "arrival_time");
        Integer departureSeconds = parseGtfsTime(record, "departure_time");

        if (arrivalSeconds == null && departureSeconds == null) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Stop time requires arrival_time or departure_time"
            );
        }

        StopTime stopTime = new StopTime();
        stopTime.setTripId(tripId);
        stopTime.setStopId(stopId);
        stopTime.setStopSequence(parseRequiredInteger(record, "stop_sequence"));
        stopTime.setArrivalSeconds(arrivalSeconds);
        stopTime.setDepartureSeconds(departureSeconds);
        stopTime.setPickupType(parseOptionalShort(record, "pickup_type"));
        stopTime.setDropOffType(parseOptionalShort(record, "drop_off_type"));

        return stopTime;
    }

    private void saveBatch(List<StopTime> batch) {
        boolean saved = stopTimeService.saveBatch(batch, BATCH_SIZE);

        if (!saved) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Unable to save imported stop times"
            );
        }
    }

    private Integer parseGtfsTime(CSVRecord record, String columnName) {
        String value = optionalValue(record, columnName);

        if (value == null) {
            return null;
        }

        String[] parts = value.split(":", -1);

        if (parts.length != 3) {
            throw invalidValue(record, columnName);
        }

        try {
            int hours = Integer.parseInt(parts[0]);
            int minutes = Integer.parseInt(parts[1]);
            int seconds = Integer.parseInt(parts[2]);

            if (hours < 0 || minutes < 0 || minutes > 59 || seconds < 0 || seconds > 59) {
                throw invalidValue(record, columnName);
            }

            return hours * 3_600 + minutes * 60 + seconds;
        } catch (NumberFormatException exception) {
            throw invalidValue(record, columnName);
        }
    }

    private Integer parseRequiredInteger(CSVRecord record, String columnName) {
        String value = requiredValue(record, columnName);

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidValue(record, columnName);
        }
    }

    private Short parseOptionalShort(CSVRecord record, String columnName) {
        String value = optionalValue(record, columnName);

        if (value == null) {
            return null;
        }

        try {
            return Short.parseShort(value);
        } catch (NumberFormatException exception) {
            throw invalidValue(record, columnName);
        }
    }

    private BusinessException invalidValue(CSVRecord record, String columnName) {
        return new BusinessException(
                ErrorCode.VALIDATION_ERROR,
                "Invalid " + columnName + " at CSV record " + record.getRecordNumber()
        );
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

    private void deleteTemporaryFile(Path temporaryFile) {
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException ignored) {
            // The operating system will remove remaining temporary files later.
        }
    }

    private record StagedGtfsArchive(Path path, String checksum) {
    }
}