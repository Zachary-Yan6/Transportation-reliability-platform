package com.zachary.transportation_reliability_platform.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Externalised settings for JWT signing, browser CORS, and admin bootstrap. */
@ConfigurationProperties(prefix = "app.security")
public record SecurityProperties(
        String jwtSecret,
        long jwtExpirationSeconds,
        String frontendOrigin,
        String bootstrapAdminEmail,
        String bootstrapAdminPassword
) {
}
