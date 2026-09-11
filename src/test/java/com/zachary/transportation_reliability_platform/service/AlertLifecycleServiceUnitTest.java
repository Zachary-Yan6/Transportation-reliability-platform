package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.zachary.transportation_reliability_platform.dto.NtaServiceAlertResponse;
import com.zachary.transportation_reliability_platform.dto.RouteAlertEvaluationResponse;
import com.zachary.transportation_reliability_platform.dto.RouteAnomalyResponse;
import com.zachary.transportation_reliability_platform.entity.NtaServiceAlert;
import com.zachary.transportation_reliability_platform.entity.ServiceAlert;
import com.zachary.transportation_reliability_platform.service.impl.NtaServiceAlertServiceImpl;
import com.zachary.transportation_reliability_platform.service.impl.ServiceAlertServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Unit tests for app-generated and official NTA alert lifecycle decisions. */
class AlertLifecycleServiceUnitTest {

    @Test
    void anomalyLifecycleCreatesRefreshesKeepsAndResolvesTheRouteAlert() {
        RouteAnomalyDetectionService detector = mock(RouteAnomalyDetectionService.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<ServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        ServiceAlertServiceImpl service = spy(new ServiceAlertServiceImpl(detector));
        doReturn(query).when(service).lambdaQuery();
        when(query.eq(any(SFunction.class), any())).thenReturn(query);
        ServiceAlert existing = alert("ACTIVE", "MEDIUM");
        when(query.one()).thenReturn(null, existing, existing, existing, null);
        doReturn(true).when(service).save(any(ServiceAlert.class));
        doReturn(true).when(service).updateById(any(ServiceAlert.class));
        when(detector.checkRoute(1L, 24)).thenReturn(
                anomaly(true, "HIGH"),
                anomaly(true, "HIGH"),
                anomaly(false, "INSUFFICIENT_DATA"),
                anomaly(false, "NONE"),
                anomaly(false, "NONE")
        );

        RouteAlertEvaluationResponse created = service.evaluateRouteAnomaly(1L, 24);
        RouteAlertEvaluationResponse refreshed = service.evaluateRouteAnomaly(1L, 24);
        RouteAlertEvaluationResponse insufficient = service.evaluateRouteAnomaly(1L, 24);
        RouteAlertEvaluationResponse resolved = service.evaluateRouteAnomaly(1L, 24);
        RouteAlertEvaluationResponse noAlert = service.evaluateRouteAnomaly(1L, 24);

        assertThat(created.alert()).isNotNull();
        assertThat(created.alert().status()).isEqualTo("ACTIVE");
        assertThat(refreshed.alert().severity()).isEqualTo("HIGH");
        assertThat(insufficient.alert().status()).isEqualTo("ACTIVE");
        assertThat(resolved.alert().status()).isEqualTo("RESOLVED");
        assertThat(resolved.alert().resolvedAt()).isNotNull();
        assertThat(noAlert.alert()).isNull();
        verify(service).save(any(ServiceAlert.class));
        verify(service, org.mockito.Mockito.times(2)).updateById(any(ServiceAlert.class));
    }

    @Test
    void routeAlertQueryClampsLimitAndMapsStoredAlertsToResponses() {
        RouteAnomalyDetectionService detector = mock(RouteAnomalyDetectionService.class);
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<ServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        ServiceAlertServiceImpl service = spy(new ServiceAlertServiceImpl(detector));
        doReturn(query).when(service).lambdaQuery();
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        when(query.list()).thenReturn(List.of(alert("ACTIVE", "HIGH")));

        assertThat(service.findRecentAlerts(999)).singleElement().satisfies(response -> {
            assertThat(response.status()).isEqualTo("ACTIVE");
            assertThat(response.severity()).isEqualTo("HIGH");
        });
        verify(query).last("LIMIT 100");
    }

    @Test
    void officialNtaAlertsApplyActiveFilterAndSplitAffectedRouteAndStopIds() {
        @SuppressWarnings("unchecked")
        LambdaQueryChainWrapper<NtaServiceAlert> query = mock(LambdaQueryChainWrapper.class);
        NtaServiceAlertServiceImpl service = spy(new NtaServiceAlertServiceImpl());
        doReturn(query).when(service).lambdaQuery();
        when(query.eq(any(Boolean.class), any(SFunction.class), any())).thenReturn(query);
        when(query.orderByDesc(any(SFunction.class))).thenReturn(query);
        when(query.last(anyString())).thenReturn(query);
        NtaServiceAlert alert = new NtaServiceAlert();
        alert.setId(9L);
        alert.setExternalAlertId("NTA-9");
        alert.setHeaderText("Diversion");
        alert.setExternalRouteIds("R1,,R2");
        alert.setExternalStopIds("S1,S2");
        alert.setStatus("ACTIVE");
        alert.setLastSeenAt(OffsetDateTime.parse("2026-09-11T12:00:00Z"));
        when(query.list()).thenReturn(List.of(alert));

        List<NtaServiceAlertResponse> responses = service.findAlerts(true, 0);

        assertThat(responses).singleElement().satisfies(response -> {
            assertThat(response.externalRouteIds()).containsExactly("R1", "R2");
            assertThat(response.externalStopIds()).containsExactly("S1", "S2");
        });
        verify(query).last("LIMIT 1");
    }

    private static RouteAnomalyResponse anomaly(boolean detected, String severity) {
        return new RouteAnomalyResponse(
                1L, "1", 24, 20L, null, null, detected, severity, "Reason"
        );
    }

    private static ServiceAlert alert(String status, String severity) {
        ServiceAlert alert = new ServiceAlert();
        alert.setId(1L);
        alert.setRouteId(1L);
        alert.setAlertType("DELAY_ANOMALY");
        alert.setStatus(status);
        alert.setSeverity(severity);
        alert.setMessage("Reason");
        alert.setFirstDetectedAt(OffsetDateTime.parse("2026-09-11T12:00:00Z"));
        alert.setLastDetectedAt(OffsetDateTime.parse("2026-09-11T12:00:00Z"));
        return alert;
    }
}
