package com.zachary.transportation_reliability_platform.dto.auth;

/** Safe account data that may be returned to a browser. */
public record CurrentUserResponse(
        Long id,
        String email,
        String role
) {
}
