package com.zachary.transportation_reliability_platform.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    RESOURCE_NOT_FOUND(
            "RESOURCE_NOT_FOUND",
            "Requested resource was not found",
            HttpStatus.NOT_FOUND
    ),

    VALIDATION_ERROR(
            "VALIDATION_ERROR",
            "Request validation failed",
            HttpStatus.BAD_REQUEST
    ),

    DUPLICATE_FEED_VERSION(
            "DUPLICATE_FEED_VERSION",
            "This GTFS feed has already been imported",
            HttpStatus.CONFLICT
    ),

    FILE_TOO_LARGE(
            "FILE_TOO_LARGE",
            "Uploaded file exceeds the allowed size",
            HttpStatus.PAYLOAD_TOO_LARGE
    ),

    GTFS_FILE_MISMATCH(
            "GTFS_FILE_MISMATCH",
            "Uploaded GTFS ZIP does not match the selected feed version",
            HttpStatus.BAD_REQUEST
    ),

    GTFS_COMPONENT_ALREADY_IMPORTED(
            "GTFS_COMPONENT_ALREADY_IMPORTED",
            "This GTFS component has already been imported",
            HttpStatus.CONFLICT
    ),

    INTERNAL_ERROR(
            "INTERNAL_ERROR",
            "An unexpected server error occurred",
            HttpStatus.INTERNAL_SERVER_ERROR
    );


    private final String code;
    private final String defaultMessage;
    private final HttpStatus httpStatus;
}