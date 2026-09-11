package com.zachary.transportation_reliability_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for periodically downloading NTA's static GTFS archive. */
@ConfigurationProperties(prefix = "app.nta.static-gtfs")
public record NtaStaticGtfsProperties(
        String url,
        int maxArchiveBytes
) {
}
