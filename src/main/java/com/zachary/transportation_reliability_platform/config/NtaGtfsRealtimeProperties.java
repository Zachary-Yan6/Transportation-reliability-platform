package com.zachary.transportation_reliability_platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration required to call the NTA GTFS-Realtime API.
 */
@ConfigurationProperties(prefix = "app.nta.gtfs-realtime")
public record NtaGtfsRealtimeProperties(
        String url,
        String vehiclesUrl,
        String alertsUrl,
        String apiKey,
        String subscriptionKeyHeader
) {
}
