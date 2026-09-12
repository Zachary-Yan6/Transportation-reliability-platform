package com.zachary.transportation_reliability_platform.dto.auth;

import java.time.OffsetDateTime;

/** JWT and account profile returned after a successful login or registration. */
public record AuthResponse(
        String accessToken,
        String tokenType,
        OffsetDateTime expiresAt,
        CurrentUserResponse user
) {
}
