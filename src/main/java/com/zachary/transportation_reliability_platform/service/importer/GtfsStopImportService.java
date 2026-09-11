package com.zachary.transportation_reliability_platform.service.importer;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.StopService;
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
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
public class GtfsStopImportService {

    private final FeedVersionService feedVersionService;
    private final StopService stopService;

    @Transactional
    public int importStops(InputStream gtfsZipInputStream, Long feedVersionId) {
        FeedVersion feedVersion = feedVersionService.getRequiredById(feedVersionId);

        byte[] zipBytes = readZipBytes(gtfsZipInputStream);
        String checksum = calculateSha256(zipBytes);

        if (!feedVersion.getChecksum().equals(checksum)) {
            throw new BusinessException(
                    ErrorCode.GTFS_FILE_MISMATCH,
                    "Uploaded GTFS ZIP does not match feed version: " + feedVersionId
            );
        }

        long existingStopCount = stopService.count(
                Wrappers.<Stop>lambdaQuery()
                        .eq(Stop::getFeedVersionId, feedVersionId)
        );

        if (existingStopCount > 0) {
            throw new BusinessException(
                    ErrorCode.GTFS_COMPONENT_ALREADY_IMPORTED,
                    "Stops have already been imported for feed version: " + feedVersionId
            );
        }

        List<Stop> stops = parseStops(zipBytes, feedVersionId);

        if (stops.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "stops.txt does not contain any stops"
            );
        }

        boolean saved = stopService.saveBatch(stops, 500);

        if (!saved) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Unable to save imported stops"
            );
        }

        return stops.size();
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

    private List<Stop> parseStops(byte[] zipBytes, Long feedVersionId) {
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(zipBytes),
                StandardCharsets.UTF_8
        )) {
            ZipEntry entry;

            while ((entry = zipInputStream.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().equalsIgnoreCase("stops.txt")) {
                    return parseStopsCsv(zipInputStream, feedVersionId);
                }
            }

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file does not contain stops.txt"
            );
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to parse GTFS ZIP file"
            );
        }
    }

    private List<Stop> parseStopsCsv(
            InputStream inputStream,
            Long feedVersionId
    ) throws IOException {
        CSVFormat csvFormat = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .get();

        List<Stop> stops = new ArrayList<>();

        try (
                Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                CSVParser parser = CSVParser.parse(reader, csvFormat)
        ) {
            for (CSVRecord record : parser) {
                Stop stop = new Stop();
                stop.setFeedVersionId(feedVersionId);
                stop.setExternalStopId(requiredValue(record, "stop_id"));
                stop.setStopName(requiredValue(record, "stop_name"));
                stop.setLatitude(parseDecimal(record, "stop_lat"));
                stop.setLongitude(parseDecimal(record, "stop_lon"));

                stops.add(stop);
            }
        }

        return stops;
    }

    private BigDecimal parseDecimal(CSVRecord record, String columnName) {
        String value = optionalValue(record, columnName);

        if (value == null) {
            return null;
        }

        try {
            return new BigDecimal(value);
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