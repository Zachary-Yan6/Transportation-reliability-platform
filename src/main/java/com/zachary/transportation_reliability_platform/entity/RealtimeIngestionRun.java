package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Audit record for one automatic NTA polling attempt.
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("realtime_ingestion_runs")
public class RealtimeIngestionRun {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long feedVersionId;
    private String targetExternalRouteId;

    private OffsetDateTime feedTimestamp;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;

    private Integer scannedStopTimeUpdates;
    private Integer publishedEventCount;
    private Integer skippedUpdateCount;

    // SUCCESS or FAILED.
    private String status;

    // Filled only when the polling attempt failed.
    private String errorMessage;
}