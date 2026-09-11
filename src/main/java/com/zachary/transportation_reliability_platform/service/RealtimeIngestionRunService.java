package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.entity.RealtimeIngestionRun;

import java.time.Instant;
import java.util.List;

/**
 * Stores the outcome of automatic NTA polling runs.
 */
public interface RealtimeIngestionRunService
        extends IService<RealtimeIngestionRun> {

    void recordSuccess(
            Long feedVersionId,
            String targetExternalRouteId,
            Instant startedAt,
            NtaRealtimeIngestionResponse result
    );

    void recordFailure(
            Long feedVersionId,
            String targetExternalRouteId,
            Instant startedAt,
            Exception exception
    );

    List<RealtimeIngestionRun> findRecent(int limit);
}