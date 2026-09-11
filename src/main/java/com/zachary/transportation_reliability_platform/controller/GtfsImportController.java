package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.*;
import com.zachary.transportation_reliability_platform.service.importer.*;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/gtfs")
public class GtfsImportController {

    private final GtfsRouteImportService gtfsRouteImportService;

    private final GtfsStopImportService gtfsStopImportService;

    private final GtfsTripImportService gtfsTripImportService;

    private final GtfsStopTimeImportService gtfsStopTimeImportService;

    private final GtfsCalendarImportService gtfsCalendarImportService;

    private final GtfsCalendarDateImportService gtfsCalendarDateImportService;

    @PostMapping(
            value = "/routes/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public GtfsRouteImportResponse importRoutes(
            @RequestParam("file") MultipartFile file,
            @RequestParam("sourceUri") String sourceUri,
            @RequestParam(value = "effectiveFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate effectiveFrom
    ) {
        if (file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file must not be empty"
            );
        }

        try {
            int importedRouteCount = gtfsRouteImportService.importRoutes(
                    file.getInputStream(),
                    sourceUri,
                    effectiveFrom
            );

            return new GtfsRouteImportResponse(
                    importedRouteCount,
                    sourceUri,
                    effectiveFrom
            );
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to read uploaded GTFS ZIP file"
            );
        }
    }

    @PostMapping(
            value = "/stops/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public GtfsStopImportResponse importStops(
            @RequestParam("file") MultipartFile file,
            @RequestParam("feedVersionId") Long feedVersionId
    ) {
        if (file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file must not be empty"
            );
        }

        try {
            int importedStopCount = gtfsStopImportService.importStops(
                    file.getInputStream(),
                    feedVersionId
            );

            return new GtfsStopImportResponse(feedVersionId, importedStopCount);
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to read uploaded GTFS ZIP file"
            );
        }
    }

    @PostMapping(
            value = "/trips/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public GtfsTripImportResponse importTrips(
            @RequestParam("file") MultipartFile file,
            @RequestParam("feedVersionId") Long feedVersionId
    ) {
        if (file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file must not be empty"
            );
        }

        try {
            int importedTripCount = gtfsTripImportService.importTrips(
                    file.getInputStream(),
                    feedVersionId
            );

            return new GtfsTripImportResponse(feedVersionId, importedTripCount);
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to read uploaded GTFS ZIP file"
            );
        }
    }

    @PostMapping(
            value = "/stop-times/import",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    @ResponseStatus(HttpStatus.CREATED)
    public GtfsStopTimeImportResponse importStopTimes(
            @RequestParam("file") MultipartFile file,
            @RequestParam("feedVersionId") Long feedVersionId
    ) {
        if (file.isEmpty()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "GTFS ZIP file must not be empty"
            );
        }

        try {
            int importedStopTimeCount = gtfsStopTimeImportService.importStopTimes(
                    file.getInputStream(),
                    feedVersionId
            );

            return new GtfsStopTimeImportResponse(
                    feedVersionId,
                    importedStopTimeCount
            );
        } catch (IOException exception) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "Unable to read uploaded GTFS ZIP file"
            );
        }
    }

    @PostMapping("/calendars/import")
    public ResponseEntity<GtfsCalendarImportResponse> importCalendars(
            @RequestParam("file") MultipartFile file,
            @RequestParam("feedVersionId") Long feedVersionId
    ) {
        GtfsCalendarImportResponse response =
                gtfsCalendarImportService.importCalendars(file, feedVersionId);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/calendar-dates/import")
    public ResponseEntity<GtfsCalendarDateImportResponse> importCalendarDates(
            @RequestParam("file") MultipartFile file,
            @RequestParam("feedVersionId") Long feedVersionId
    ) {
        GtfsCalendarDateImportResponse response =
                gtfsCalendarDateImportService.importCalendarDates(
                        file,
                        feedVersionId
                );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}