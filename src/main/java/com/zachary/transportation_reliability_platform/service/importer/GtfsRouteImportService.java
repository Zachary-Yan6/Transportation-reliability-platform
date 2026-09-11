package com.zachary.transportation_reliability_platform.service.importer;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import com.zachary.transportation_reliability_platform.service.RouteService;
import lombok.RequiredArgsConstructor;

// Defines how CSV data should be parsed.
import org.apache.commons.csv.CSVFormat;

// Actually parses CSV content.
import org.apache.commons.csv.CSVParser;

// Represents one row in the CSV file.
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Converts a byte[] back into an InputStream.
import java.io.ByteArrayInputStream;
import java.io.IOException;

// Represents a stream of bytes.
// Your uploaded MultipartFile can provide an InputStream.
import java.io.InputStream;

// Converts byte-based InputStream data into character-based Reader data.
import java.io.InputStreamReader;

// General interface for reading characters.
import java.io.Reader;

import java.nio.charset.StandardCharsets;

import java.security.MessageDigest;

// Thrown if Java cannot find the requested hashing algorithm.
import java.security.NoSuchAlgorithmException;

// Represents a date such as 2026-09-06.
import java.time.LocalDate;

import java.util.ArrayList;

// Converts byte[] hash values into hexadecimal strings.
import java.util.HexFormat;
import java.util.List;

// Represents one file/directory inside a ZIP archive.
import java.util.zip.ZipEntry;

// Used to read ZIP files sequentially.
import java.util.zip.ZipInputStream;


@Service
@RequiredArgsConstructor
public class GtfsRouteImportService {


    private final FeedVersionService feedVersionService;

    private final RouteService routeService;

    @Transactional
    public int importRoutes(

            InputStream gtfsZipInputStream,
            // The uploaded GTFS ZIP file.
            //
            // For example:
            //
            // MultipartFile file
            //
            // file.getInputStream()
            //
            // gives you this InputStream.

            String sourceUri,

            LocalDate effectiveFrom
            // The date from which this feed should be considered valid.
            //
            // Example:
            //
            // 2026-09-01

    ) {


        if (sourceUri == null || sourceUri.isBlank()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "sourceUri must not be empty"
            );
        }


        byte[] zipBytes = readZipBytes(gtfsZipInputStream);
        // Read the entire ZIP file into memory.
        //
        // Why?
        //
        // InputStream is normally consumed as you read it.
        //
        // You need to use the uploaded file twice:
        //
        // 1. Calculate SHA-256
        // 2. Parse the ZIP
        //
        // Saving it into byte[] allows both operations.
        //
        // Conceptually:
        //
        // InputStream
        //     ↓
        //   byte[]
        //   /    \
        // SHA    ZIP parser


        String checksum = calculateSha256(zipBytes);
        // Generate a unique fingerprint for the ZIP file.
        //
        // Example:
        //
        // gtfs.zip
        //    ↓
        // SHA-256
        //    ↓
        // 8b0349ac1207...
        //
        // Same file:
        //
        // same SHA-256
        //
        // Different file:
        //
        // almost certainly different SHA-256


        feedVersionService
                .findByChecksum(checksum)
                .ifPresent(existing -> {

                    // Search DB for an existing FeedVersion
                    // with the same checksum.
                    //
                    // findByChecksum probably returns:
                    //
                    // Optional<FeedVersion>
                    //
                    // If a result exists,
                    // ifPresent executes this lambda.

                    throw new BusinessException(
                            ErrorCode.DUPLICATE_FEED_VERSION,
                            "GTFS feed has already been imported. Feed version id: "
                                    + existing.getId()
                    );

                    // Reject importing exactly the same GTFS file again.
                });


        FeedVersion feedVersion = new FeedVersion();

        feedVersion.setSourceUri(sourceUri);

        // A newly downloaded archive is not selected for matching until every
        // required GTFS component has imported successfully.
        feedVersion.setLifecycleStatus("IMPORTING");

        feedVersion.setChecksum(checksum);
        // Store the SHA-256 fingerprint.
        //
        // This enables duplicate detection later.


        feedVersion.setEffectiveFrom(effectiveFrom);
        // Store when this feed becomes effective.


        feedVersionService.save(feedVersion);

        List<Route> routes =
                parseRoutes(
                        zipBytes,
                        feedVersion.getId()
                );

        // Extract routes.txt from the ZIP,
        // parse every CSV row,
        // and create Route objects.
        //
        // Every route gets:
        //
        // feedVersionId
        //
        // so we know which feed version it belongs to.


        if (routes.isEmpty()) {
            // routes.txt exists,
            // but contains zero routes.

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "routes.txt does not contain any routes"
            );
        }


        boolean saved =
                routeService.saveBatch(routes, 500);

        // Save routes in batches.
        //
        // Instead of:
        //
        // INSERT route1
        // INSERT route2
        // INSERT route3
        // ...
        //
        // it groups them.
        //
        // Example:
        //
        // 2300 routes:
        //
        // batch 1 → 500
        // batch 2 → 500
        // batch 3 → 500
        // batch 4 → 500
        // batch 5 → 300
        //
        // This usually performs better than saving
        // each route individually.

        if (!saved) {
            // MyBatis-Plus saveBatch() reports failure.

            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Unable to save imported routes"
            );

            // Because the method is @Transactional,
            // throwing this exception should cause rollback.
        }


        return routes.size();
        // Return how many routes were successfully imported.
        //
        // Example:
        //
        // return 138;
    }


    private byte[] readZipBytes(InputStream inputStream) {
        // Helper method.
        //
        // private means:
        // only this service uses it.

        try {

            return inputStream.readAllBytes();

            // Read everything from the uploaded stream.
            //
            // Example:
            //
            // ZIP file
            //   ↓
            // [80, 75, 3, 4, ...]
            //
            // returned as byte[].

        } catch (IOException exception) {

            // File could not be read.

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to read GTFS ZIP file"
            );

            // Convert low-level IOException
            // into an application-level error.
        }
    }


    private String calculateSha256(byte[] content) {
        // Receives the entire ZIP file as bytes.
        //
        // Returns something like:
        //
        // "2cd0a0f52b5c..."

        try {


            byte[] hash =
                    MessageDigest
                            .getInstance("SHA-256")
                            .digest(content);

            // MessageDigest.getInstance("SHA-256")
            //
            // creates an SHA-256 hashing object.
            //
            // .digest(content)
            //
            // calculates the hash.
            //
            // Result is byte[].


            return HexFormat
                    .of()
                    .formatHex(hash);

            // Convert raw hash bytes into readable hexadecimal.
            //
            // byte[]:
            //
            // [-53, 12, 55, ...]
            //
            // becomes:
            //
            // "cb0c37..."
            //
            // This is much easier to store in a database.


        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception
            );

            // SHA-256 is required by standard Java implementations.
            //
            // So if this happens,
            // it is probably a serious environment/configuration problem,
            // rather than bad user input.
        }
    }


    private List<Route> parseRoutes(
            byte[] zipBytes,
            Long feedVersionId
    ) {

        // This method:
        //
        // ZIP
        //  ↓
        // find routes.txt
        //  ↓
        // parse routes.txt
        //  ↓
        // List<Route>


        try (
                ZipInputStream zipInputStream =
                        new ZipInputStream(

                                new ByteArrayInputStream(zipBytes),
                                // Convert the stored byte[]
                                // back into an InputStream.

                                StandardCharsets.UTF_8
                                // Character encoding for ZIP entry names.
                        )
        ) {

            ZipEntry entry;
            // Will represent one item inside the ZIP.
            //
            // Example GTFS ZIP:
            //
            // agency.txt
            // routes.txt
            // stops.txt
            // trips.txt
            // stop_times.txt


            while (
                    (entry = zipInputStream.getNextEntry()) != null
            ) {

                // Read ZIP entries one by one.
                //
                // First:
                //
                // agency.txt
                //
                // next:
                //
                // routes.txt
                //
                // next:
                //
                // stops.txt
                //
                // eventually:
                //
                // null
                //
                // null means no more files.


                if (
                        !entry.isDirectory()
                                &&
                                entry.getName()
                                        .equalsIgnoreCase("routes.txt")
                ) {

                    // Check:
                    //
                    // 1. Entry is a file, not a directory.
                    //
                    // AND
                    //
                    // 2. File name is routes.txt.
                    //
                    // equalsIgnoreCase means:
                    //
                    // routes.txt
                    // ROUTES.TXT
                    // Routes.txt
                    //
                    // are all accepted.


                    return parseRoutesCsv(
                            zipInputStream,
                            feedVersionId
                    );

                }
            }

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file does not contain routes.txt"
            );

            // Reached end of ZIP but never found routes.txt.
        }

        catch (IOException exception) {

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to parse GTFS ZIP file"
            );

            // Handles corrupted/unreadable ZIP files.
        }
    }


    private List<Route> parseRoutesCsv(
            InputStream inputStream,
            Long feedVersionId
    ) throws IOException {

        // Now we are no longer concerned with the whole ZIP.
        //
        // This method only deals with:
        //
        // routes.txt


        CSVFormat csvFormat =
                CSVFormat.DEFAULT
                        .builder()

                        // Start building CSV parser configuration.


                        .setHeader()

                        // Treat the first CSV line as column names.
                        //
                        // Example:
                        //
                        // route_id,agency_id,route_short_name,route_long_name,route_type
                        //
                        // Then you can write:
                        //
                        // record.get("route_id")
                        //
                        // instead of:
                        //
                        // record.get(0)


                        .setSkipHeaderRecord(true)

                        // Don't treat the header itself as a Route.
                        //
                        // Without it, this:
                        //
                        // route_id,agency_id,...
                        //
                        // could be considered data.


                        .setTrim(true)

                        // Remove surrounding whitespace.
                        //
                        // Example:
                        //
                        // " 304 "
                        //
                        // becomes:
                        //
                        // "304"


                        .get();

        // Finish building CSVFormat.


        List<Route> routes = new ArrayList<>();

        // Create an empty list that will eventually contain:
        //
        // Route
        // Route
        // Route
        // Route


        try (

                Reader reader =
                        new InputStreamReader(
                                inputStream,
                                StandardCharsets.UTF_8
                        );

                // ZIP gives us bytes.
                //
                // CSV contains text.
                //
                // InputStreamReader converts:
                //
                // bytes → characters
                //
                // UTF-8 is explicitly specified to make
                // parsing consistent across environments.


                CSVParser parser =
                        CSVParser.parse(
                                reader,
                                csvFormat
                        )

                // Build a CSV parser using:
                //
                // 1. routes.txt content
                // 2. our CSV format configuration

        ) {


            for (CSVRecord record : parser) {

                // Loop through each line.
                //
                // Example:
                //
                // 1,Dublin Bus,46A,City Centre - Phoenix Park,3
                //
                // becomes one CSVRecord.


                Route route = new Route();

                // Create one database Route entity
                // for this CSV row.


                route.setFeedVersionId(feedVersionId);

                // Connect this route to the feed version.
                //
                // Database concept:
                //
                // FeedVersion 10
                //     |
                //     +-- Route A
                //     +-- Route B
                //     +-- Route C


                route.setExternalRouteId(
                        requiredValue(
                                record,
                                "route_id"
                        )
                );

                // Get GTFS route_id.
                //
                // This field is required by your importer.
                //
                // Example:
                //
                // route_id = "46A"


                route.setAgencyId(
                        optionalValue(
                                record,
                                "agency_id"
                        )
                );

                // Get agency_id if present.
                //
                // If absent:
                //
                // null


                route.setShortName(
                        optionalValue(
                                record,
                                "route_short_name"
                        )
                );

                // Example:
                //
                // "46A"
                // "304"
                // "D1"


                route.setLongName(
                        optionalValue(
                                record,
                                "route_long_name"
                        )
                );

                // Example:
                //
                // "Limerick City - University"


                route.setRouteType(
                        parseRouteType(record)
                );

                // GTFS route_type is stored in CSV as text:
                //
                // "3"
                //
                // but your Java entity expects:
                //
                // Short 3
                //
                // parseRouteType performs conversion
                // and validation.


                routes.add(route);

                // Put this Route into our list.
            }
        }


        return routes;

        // Return all parsed Route entities.
    }


    private Short parseRouteType(CSVRecord record) {

        // Parse route_type separately because
        // it needs type conversion.


        String routeType =
                requiredValue(
                        record,
                        "route_type"
                );

        // First retrieve and validate the value.
        //
        // CSV gives:
        //
        // "3"


        try {

            return Short.parseShort(routeType);

            // Convert:
            //
            // "3"
            //
            // into:
            //
            // Short 3


        } catch (NumberFormatException exception) {

            // Example bad input:
            //
            // route_type = "BUS"
            //
            // Short.parseShort("BUS")
            //
            // throws NumberFormatException.


            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Invalid route_type at CSV record "
                            + record.getRecordNumber()
            );

            // Give the user a much more meaningful error.
            //
            // Example:
            //
            // Invalid route_type at CSV record 73
        }
    }


    private String requiredValue(
            CSVRecord record,
            String columnName
    ) {

        // Helper method for fields that MUST exist
        // and MUST contain a value.


        String value =
                optionalValue(
                        record,
                        columnName
                );

        // Reuse optionalValue's logic.
        //
        // It already handles:
        //
        // missing column
        // null
        // empty string
        // whitespace
        // trimming


        if (value == null) {

            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Missing "
                            + columnName
                            + " at CSV record "
                            + record.getRecordNumber()
            );

            // Example:
            //
            // Missing route_id at CSV record 16
        }


        return value;

        // If it exists and is valid,
        // return it.
    }


    private String optionalValue(
            CSVRecord record,
            String columnName
    ) {

        // Helper method for fields that may be missing.


        if (!record.isMapped(columnName)) {

            // Check whether this column exists in the CSV header.
            //
            // Example:
            //
            // We request:
            //
            // agency_id
            //
            // but header is:
            //
            // route_id,route_short_name,route_type
            //
            // Then agency_id is not mapped.

            return null;
        }


        String value =
                record.get(columnName);

        // Read the field from the CSV row.


        return value == null || value.isBlank()
                ? null
                : value.trim();

    }
}
