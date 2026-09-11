package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.config.NtaGtfsRealtimeProperties;
import com.zachary.transportation_reliability_platform.config.NtaStaticGtfsProperties;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.client.NtaStaticGtfsClient;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests HTTP-client validation and response-size safeguards without NTA access. */
class NtaApiClientsUnitTest {

    @Test
    void realtimeClientUsesConfiguredUrlsAndApiKeyHeaderForAllThreeFeeds() {
        HttpChain chain = httpChain();
        when(chain.response.body(String.class)).thenReturn("{\"entity\":[]}");
        NtaGtfsRealtimeClient client = new NtaGtfsRealtimeClient(
                chain.builder,
                new NtaGtfsRealtimeProperties(
                        "https://nta.example/trips", "https://nta.example/vehicles",
                        "https://nta.example/alerts", "secret", "x-api-key"
                )
        );

        assertThat(client.fetchRawFeed()).isEqualTo("{\"entity\":[]}");
        assertThat(client.fetchRawVehicleFeed()).isEqualTo("{\"entity\":[]}");
        assertThat(client.fetchRawAlertsFeed()).isEqualTo("{\"entity\":[]}");
        verify(chain.request).uri("https://nta.example/trips");
        verify(chain.request).uri("https://nta.example/vehicles");
        verify(chain.request).uri("https://nta.example/alerts");
        verify(chain.headers, org.mockito.Mockito.times(3)).header("x-api-key", "secret");
    }

    @Test
    void realtimeClientRejectsMissingKeyOrMissingOperationUrlBeforeHttpCall() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        NtaGtfsRealtimeClient missingKey = new NtaGtfsRealtimeClient(
                builder,
                new NtaGtfsRealtimeProperties("https://nta.example/trips", "vehicles", "alerts", " ", "x-api-key")
        );
        NtaGtfsRealtimeClient missingVehiclesUrl = new NtaGtfsRealtimeClient(
                builder,
                new NtaGtfsRealtimeProperties("https://nta.example/trips", " ", "alerts", "secret", "x-api-key")
        );

        assertThatThrownBy(missingKey::fetchRawFeed).isInstanceOf(BusinessException.class);
        assertThatThrownBy(missingVehiclesUrl::fetchRawVehicleFeed)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void realtimeAndStaticClientsTranslateRestClientFailures() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        when(builder.build()).thenThrow(new RestClientException("offline"));
        NtaGtfsRealtimeClient realtime = new NtaGtfsRealtimeClient(
                builder,
                new NtaGtfsRealtimeProperties("trips", "vehicles", "alerts", "secret", "x-api-key")
        );
        NtaStaticGtfsClient staticClient = new NtaStaticGtfsClient(
                builder, new NtaStaticGtfsProperties("https://nta.example/static.zip", 100)
        );

        assertThatThrownBy(realtime::fetchRawFeed).isInstanceOf(BusinessException.class);
        assertThatThrownBy(staticClient::downloadArchive).isInstanceOf(BusinessException.class);
    }

    @Test
    void staticClientAcceptsOnlyNonEmptyArchivesWithinTheConfiguredLimit() {
        HttpChain chain = httpChain();
        when(chain.response.body(byte[].class)).thenReturn(
                new byte[]{1, 2}, new byte[0], new byte[]{1, 2, 3, 4}
        );
        NtaStaticGtfsClient client = new NtaStaticGtfsClient(
                chain.builder, new NtaStaticGtfsProperties("https://nta.example/static.zip", 3)
        );

        assertThat(client.downloadArchive()).containsExactly(1, 2);
        assertThatThrownBy(client::downloadArchive).isInstanceOf(BusinessException.class);
        assertThatThrownBy(client::downloadArchive).isInstanceOf(BusinessException.class);
        verify(chain.request, org.mockito.Mockito.times(3)).uri("https://nta.example/static.zip");
    }

    @Test
    void staticClientRejectsABlankDownloadUrl() {
        NtaStaticGtfsClient client = new NtaStaticGtfsClient(
                mock(RestClient.Builder.class), new NtaStaticGtfsProperties(" ", 3)
        );

        assertThatThrownBy(client::downloadArchive).isInstanceOf(BusinessException.class);
    }

    private static HttpChain httpChain() {
        RestClient.Builder builder = mock(RestClient.Builder.class);
        RestClient restClient = mock(RestClient.class);
        @SuppressWarnings("rawtypes")
        RestClient.RequestHeadersUriSpec request = mock(RestClient.RequestHeadersUriSpec.class);
        @SuppressWarnings("rawtypes")
        RestClient.RequestHeadersSpec headers = mock(RestClient.RequestHeadersSpec.class);
        RestClient.ResponseSpec response = mock(RestClient.ResponseSpec.class);
        when(builder.build()).thenReturn(restClient);
        when(restClient.get()).thenReturn(request);
        when(request.uri(anyString())).thenReturn(headers);
        when(headers.header(anyString(), anyString())).thenReturn(headers);
        when(headers.retrieve()).thenReturn(response);
        return new HttpChain(builder, request, headers, response);
    }

    private record HttpChain(
            RestClient.Builder builder,
            RestClient.RequestHeadersUriSpec<?> request,
            RestClient.RequestHeadersSpec<?> headers,
            RestClient.ResponseSpec response
    ) {
    }
}
