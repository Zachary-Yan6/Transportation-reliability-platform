package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.response.NtaServiceAlertSyncResponse;
import com.zachary.transportation_reliability_platform.entity.NtaServiceAlert;
import com.zachary.transportation_reliability_platform.service.client.NtaGtfsRealtimeClient;
import com.zachary.transportation_reliability_platform.service.impl.NtaServiceAlertIngestionService;
import com.zachary.transportation_reliability_platform.websocket.LiveUpdateBroadcaster;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Tests snapshot-based official alert synchronization with a real JSON parser. */
class NtaServiceAlertIngestionServiceUnitTest {

    @Test
    void syncResolvesDisappearedAlertsCreatesNewAlertAndBroadcastsOneChange() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        NtaServiceAlertService alertService = mock(NtaServiceAlertService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<NtaServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        @SuppressWarnings("unchecked")
        LambdaUpdateChainWrapper<NtaServiceAlert> update = mock(LambdaUpdateChainWrapper.class);
        NtaServiceAlert previous = new NtaServiceAlert();
        previous.setExternalAlertId("OLD");
        when(client.fetchRawAlertsFeed()).thenReturn(alertFeed());
        when(alertService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of(previous));
        when(query.one()).thenReturn(null);
        when(alertService.lambdaUpdate()).thenReturn(update);
        when(update.set(any(SFunction.class), any())).thenReturn(update);
        when(update.eq(any(SFunction.class), eq("ACTIVE"))).thenReturn(update);
        when(update.update()).thenReturn(true);
        when(alertService.save(any(NtaServiceAlert.class))).thenReturn(true);
        NtaServiceAlertIngestionService ingestionService = new NtaServiceAlertIngestionService(
                client, alertService, new ObjectMapper(), broadcaster
        );

        NtaServiceAlertSyncResponse response = ingestionService.synchronizeAlerts();

        ArgumentCaptor<NtaServiceAlert> captor = ArgumentCaptor.forClass(NtaServiceAlert.class);
        verify(alertService).save(captor.capture());
        NtaServiceAlert saved = captor.getValue();
        assertThat(response.scannedAlertCount()).isEqualTo(1);
        assertThat(response.savedAlertCount()).isEqualTo(1);
        assertThat(response.resolvedAlertCount()).isEqualTo(1);
        assertThat(saved)
                .extracting(NtaServiceAlert::getExternalAlertId,
                        NtaServiceAlert::getHeaderText,
                        NtaServiceAlert::getExternalRouteIds,
                        NtaServiceAlert::getExternalStopIds,
                        NtaServiceAlert::getStatus)
                .containsExactly("A1", "Diversion in city centre", "R1,R2", "S1,S2", "ACTIVE");
        assertThat(saved.getActiveFrom()).isEqualTo(OffsetDateTime.parse("2026-09-11T12:00:00Z"));
        assertThat(saved.getActiveUntil()).isEqualTo(OffsetDateTime.parse("2026-09-11T14:00:00Z"));
        verify(broadcaster).signalChange("service-alerts");
    }

    @Test
    void syncUpdatesAnExistingAlertAndRejectsMalformedJson() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        NtaServiceAlertService alertService = mock(NtaServiceAlertService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<NtaServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        @SuppressWarnings("unchecked")
        LambdaUpdateChainWrapper<NtaServiceAlert> update = mock(LambdaUpdateChainWrapper.class);
        NtaServiceAlert existing = new NtaServiceAlert();
        existing.setId(3L);
        existing.setExternalAlertId("A1");
        when(client.fetchRawAlertsFeed()).thenReturn(alertFeed());
        when(alertService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        when(query.one()).thenReturn(existing);
        when(alertService.lambdaUpdate()).thenReturn(update);
        when(update.set(any(SFunction.class), any())).thenReturn(update);
        when(update.eq(any(SFunction.class), eq("ACTIVE"))).thenReturn(update);
        when(update.update()).thenReturn(true);
        when(alertService.updateById(existing)).thenReturn(true);
        NtaServiceAlertIngestionService ingestionService = new NtaServiceAlertIngestionService(
                client, alertService, new ObjectMapper(), broadcaster
        );

        ingestionService.synchronizeAlerts();
        verify(alertService).updateById(existing);

        when(client.fetchRawAlertsFeed()).thenReturn("not-json");
        assertThatThrownBy(ingestionService::synchronizeAlerts)
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void syncSkipsInvalidEntitiesAndBuildsFallbackTextForSparseValidAlerts() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        NtaServiceAlertService alertService = mock(NtaServiceAlertService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<NtaServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        @SuppressWarnings("unchecked")
        LambdaUpdateChainWrapper<NtaServiceAlert> update = mock(LambdaUpdateChainWrapper.class);
        when(client.fetchRawAlertsFeed()).thenReturn("""
                {"header":{"timestamp":"not-a-number"},"entity":[
                  {"id":"missing-alert"},
                  {"alert":{}},
                  {"id":"A2","alert":{"description_text":{"translation":[{}]},
                    "informed_entity":[{"route_id":" ","stop_id":""}],
                    "active_period":[{"start":"invalid","end":""}]}}
                ]}
                """);
        when(alertService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        when(query.one()).thenReturn(null);
        when(alertService.lambdaUpdate()).thenReturn(update);
        when(update.set(any(SFunction.class), any())).thenReturn(update);
        when(update.eq(any(SFunction.class), eq("ACTIVE"))).thenReturn(update);
        when(update.update()).thenReturn(true);
        when(alertService.save(any(NtaServiceAlert.class))).thenReturn(true);
        NtaServiceAlertIngestionService ingestionService = new NtaServiceAlertIngestionService(
                client, alertService, new ObjectMapper(), broadcaster
        );

        NtaServiceAlertSyncResponse response = ingestionService.synchronizeAlerts();

        ArgumentCaptor<NtaServiceAlert> captor = ArgumentCaptor.forClass(NtaServiceAlert.class);
        verify(alertService).save(captor.capture());
        assertThat(response.scannedAlertCount()).isEqualTo(1);
        assertThat(captor.getValue())
                .extracting(NtaServiceAlert::getHeaderText,
                        NtaServiceAlert::getExternalRouteIds,
                        NtaServiceAlert::getExternalStopIds,
                        NtaServiceAlert::getActiveFrom,
                        NtaServiceAlert::getActiveUntil)
                .containsExactly("NTA service alert A2", "", "", null, null);
    }

    @Test
    void syncTrimsTranslationsUsesDescriptionFallbackAndKeepsTheWidestActivePeriod() {
        NtaGtfsRealtimeClient client = mock(NtaGtfsRealtimeClient.class);
        NtaServiceAlertService alertService = mock(NtaServiceAlertService.class);
        LiveUpdateBroadcaster broadcaster = mock(LiveUpdateBroadcaster.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<NtaServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        @SuppressWarnings("unchecked")
        LambdaUpdateChainWrapper<NtaServiceAlert> update = mock(LambdaUpdateChainWrapper.class);
        when(client.fetchRawAlertsFeed()).thenReturn("""
                {"header":{"timestamp":null},"entity":[
                  {"id":"","alert":{}},
                  {"id":"A3","alert":{
                    "header_text":{"translation":[{"text":null},{"text":"  "}]},
                    "description_text":{"translation":[{"text":"Use another stop"}]},
                    "informed_entity":[
                      {}, {"route_id":"  R3  ","stop_id":" S3 "},
                      {"route_id":"R3","stop_id":"S3"}
                    ],
                    "active_period":[
                      {"start":"1789131600","end":"1789131600"},
                      {"start":"1789128000","end":"1789138800"},
                      {"start":"0","end":"-1"}
                    ]
                  }}
                ]}
                """);
        when(alertService.lambdaQuery()).thenReturn(query);
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        when(query.list()).thenReturn(List.of());
        when(query.one()).thenReturn(null);
        when(alertService.lambdaUpdate()).thenReturn(update);
        when(update.set(any(SFunction.class), any())).thenReturn(update);
        when(update.eq(any(SFunction.class), eq("ACTIVE"))).thenReturn(update);
        when(update.update()).thenReturn(true);
        when(alertService.save(any(NtaServiceAlert.class))).thenReturn(true);
        NtaServiceAlertIngestionService ingestionService = new NtaServiceAlertIngestionService(
                client, alertService, new ObjectMapper(), broadcaster
        );

        NtaServiceAlertSyncResponse response = ingestionService.synchronizeAlerts();

        ArgumentCaptor<NtaServiceAlert> captor = ArgumentCaptor.forClass(NtaServiceAlert.class);
        verify(alertService).save(captor.capture());
        assertThat(response.feedTimestamp()).isNotNull();
        assertThat(response.scannedAlertCount()).isEqualTo(1);
        assertThat(captor.getValue())
                .extracting(NtaServiceAlert::getHeaderText,
                        NtaServiceAlert::getExternalRouteIds,
                        NtaServiceAlert::getExternalStopIds,
                        NtaServiceAlert::getActiveFrom,
                        NtaServiceAlert::getActiveUntil)
                .containsExactly(
                        "Use another stop", "R3", "S3",
                        OffsetDateTime.parse("2026-09-11T12:00:00Z"),
                        OffsetDateTime.parse("2026-09-11T15:00:00Z")
                );
    }

    private static String alertFeed() {
        return """
                {
                  "header": {"timestamp": "1789128000"},
                  "entity": [{
                    "id": "A1",
                    "alert": {
                      "header_text": {"translation": [{"text": "Diversion in city centre"}]},
                      "description_text": {"translation": [{"text": "Road works"}]},
                      "cause": "CONSTRUCTION", "effect": "DETOUR", "severity_level": "SEVERE",
                      "informed_entity": [
                        {"route_id": "R1", "stop_id": "S1"},
                        {"route_id": "R2", "stop_id": "S2"},
                        {"route_id": "R1", "stop_id": "S1"}
                      ],
                      "active_period": [
                        {"start": "1789128000", "end": "1789135200"},
                        {"start": "1789131600", "end": "1789131600"}
                      ]
                    }
                  }]
                }
                """;
    }
}
