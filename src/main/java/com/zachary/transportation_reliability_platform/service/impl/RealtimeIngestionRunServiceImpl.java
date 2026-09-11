package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.dto.response.NtaRealtimeIngestionResponse;
import com.zachary.transportation_reliability_platform.entity.RealtimeIngestionRun;
import com.zachary.transportation_reliability_platform.mapper.RealtimeIngestionRunMapper;
import com.zachary.transportation_reliability_platform.service.RealtimeIngestionRunService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Service
public class RealtimeIngestionRunServiceImpl
        extends ServiceImpl<
                RealtimeIngestionRunMapper,
                RealtimeIngestionRun
                >
        implements RealtimeIngestionRunService {

    /**
     * Saves a successful polling result independently from other transactions.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(
            Long feedVersionId,
            String targetExternalRouteId,
            Instant startedAt,
            NtaRealtimeIngestionResponse result
    ) {
        RealtimeIngestionRun run = new RealtimeIngestionRun();

        run.setFeedVersionId(feedVersionId);
        run.setTargetExternalRouteId(targetExternalRouteId);

        run.setFeedTimestamp(toUtc(result.feedTimestamp()));
        run.setStartedAt(toUtc(startedAt));
        run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));

        run.setScannedStopTimeUpdates(result.scannedStopTimeUpdates());
        run.setPublishedEventCount(result.publishedEventCount());
        run.setSkippedUpdateCount(result.skippedUpdateCount());

        run.setStatus("SUCCESS");
        run.setErrorMessage(null);

        save(run);
    }

    /**
     * Saves a failed polling attempt so collection gaps remain visible.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(
            Long feedVersionId,
            String targetExternalRouteId,
            Instant startedAt,
            Exception exception
    ) {
        RealtimeIngestionRun run = new RealtimeIngestionRun();

        run.setFeedVersionId(feedVersionId);
        run.setTargetExternalRouteId(targetExternalRouteId);

        run.setFeedTimestamp(null);
        run.setStartedAt(toUtc(startedAt));
        run.setFinishedAt(OffsetDateTime.now(ZoneOffset.UTC));

        run.setScannedStopTimeUpdates(0);
        run.setPublishedEventCount(0);
        run.setSkippedUpdateCount(0);

        run.setStatus("FAILED");
        run.setErrorMessage(
                exception.getMessage() == null
                        ? exception.getClass().getSimpleName()
                        : exception.getMessage()
        );

        save(run);
    }

    /**
     * Returns recent polling attempts, newest first.
     */
    @Override
    public List<RealtimeIngestionRun> findRecent(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 100);

        return lambdaQuery()
                .orderByDesc(RealtimeIngestionRun::getStartedAt)
                .last("LIMIT " + safeLimit)
                .list();
    }

    private OffsetDateTime toUtc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}