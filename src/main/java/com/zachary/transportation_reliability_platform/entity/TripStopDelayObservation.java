package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@TableName("trip_stop_delay_observations")
public class TripStopDelayObservation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private UUID eventId;

    private Long feedVersionId;
    private Long tripId;
    private Long stopId;

    private Integer stopSequence;
    private Integer delaySeconds;

    private OffsetDateTime observedAt;
    private OffsetDateTime receivedAt;
}