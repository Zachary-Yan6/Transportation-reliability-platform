package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.dto.NtaServiceAlertResponse;
import com.zachary.transportation_reliability_platform.entity.NtaServiceAlert;
import com.zachary.transportation_reliability_platform.mapper.NtaServiceAlertMapper;
import com.zachary.transportation_reliability_platform.service.NtaServiceAlertService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class NtaServiceAlertServiceImpl extends ServiceImpl<NtaServiceAlertMapper, NtaServiceAlert>
        implements NtaServiceAlertService {

    @Override
    @Transactional(readOnly = true)
    public List<NtaServiceAlertResponse> findAlerts(boolean activeOnly, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);
        return lambdaQuery()
                .eq(activeOnly, NtaServiceAlert::getStatus, "ACTIVE")
                .orderByDesc(NtaServiceAlert::getLastSeenAt)
                .last("LIMIT " + safeLimit)
                .list().stream().map(this::toResponse).toList();
    }

    private NtaServiceAlertResponse toResponse(NtaServiceAlert alert) {
        return new NtaServiceAlertResponse(alert.getId(), alert.getExternalAlertId(),
                alert.getHeaderText(), alert.getDescriptionText(), alert.getCause(),
                alert.getEffect(), alert.getSeverityLevel(), alert.getActiveFrom(),
                alert.getActiveUntil(), split(alert.getExternalRouteIds()),
                split(alert.getExternalStopIds()), alert.getStatus(), alert.getLastSeenAt());
    }

    private List<String> split(String value) {
        return value == null || value.isBlank() ? List.of()
                : Arrays.stream(value.split(",")).filter(part -> !part.isBlank()).toList();
    }
}
