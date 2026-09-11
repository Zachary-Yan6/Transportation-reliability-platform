package com.zachary.transportation_reliability_platform.service.client;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.config.NtaGtfsRealtimeProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Service
@RequiredArgsConstructor
public class NtaGtfsRealtimeClient {

    // Spring Boot provides a configured builder for creating HTTP clients.
    private final RestClient.Builder restClientBuilder;

    // Holds the NTA URL, API key, and required header name: x-api-key.
    private final NtaGtfsRealtimeProperties properties;

    /**
     * Fetches the live GTFS-Realtime feed as raw JSON.
     */
    public String fetchRawFeed() {

        return fetchRawFeed(properties.url(), "NTA trip-updates URL");
    }

    /**
     * Fetches the independent NTA vehicle-location feed as raw JSON.
     */
    public String fetchRawVehicleFeed() {
        return fetchRawFeed(properties.vehiclesUrl(), "NTA vehicles URL");
    }

    /** Fetches NTA's separately configured GTFS-Realtime Alerts operation. */
    public String fetchRawAlertsFeed() {
        return fetchRawFeed(properties.alertsUrl(), "NTA alerts URL");
    }

    private String fetchRawFeed(String url, String urlDescription) {
        validateConfiguration(url, urlDescription);

        try {
            return restClientBuilder.build()
                    .get()
                    .uri(url)
                    .header(
                            properties.subscriptionKeyHeader(),
                            properties.apiKey()
                    )
                    .retrieve()
                    .body(String.class);

        } catch (RestClientResponseException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA API returned HTTP "
                            + exception.getStatusCode().value()
            );

        } catch (RestClientException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to call the NTA API: "
                            + exception.getMessage()
            );
        }
    }

    /**
     * Stops the request early when no NTA API key is configured.
     */
    private void validateConfiguration(String url, String urlDescription) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA_API_KEY environment variable is not configured"
            );
        }

        if (url == null || url.isBlank()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    urlDescription + " is not configured"
            );
        }
    }
}
