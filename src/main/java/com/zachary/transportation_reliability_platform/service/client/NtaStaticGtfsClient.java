package com.zachary.transportation_reliability_platform.service.client;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.config.NtaStaticGtfsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Downloads the static GTFS ZIP without involving a browser or local upload. */
@Service
@RequiredArgsConstructor
public class NtaStaticGtfsClient {

    private final RestClient.Builder restClientBuilder;
    private final NtaStaticGtfsProperties properties;

    public byte[] downloadArchive() {
        if (properties.url() == null || properties.url().isBlank()) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA static GTFS URL is not configured"
            );
        }

        try {
            byte[] archive = restClientBuilder.build()
                    .get()
                    .uri(properties.url())
                    .retrieve()
                    .body(byte[].class);

            if (archive == null || archive.length == 0) {
                throw new BusinessException(
                        ErrorCode.INTERNAL_ERROR,
                        "NTA static GTFS download was empty"
                );
            }

            if (archive.length > properties.maxArchiveBytes()) {
                throw new BusinessException(
                        ErrorCode.VALIDATION_ERROR,
                        "NTA static GTFS archive exceeds the configured size limit"
                );
            }

            return archive;
        } catch (RestClientResponseException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "NTA static GTFS returned HTTP " + exception.getStatusCode().value()
            );
        } catch (RestClientException exception) {
            throw new BusinessException(
                    ErrorCode.INTERNAL_ERROR,
                    "Failed to download static GTFS: " + exception.getMessage()
            );
        }
    }
}
