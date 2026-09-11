package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** An official disruption supplied by NTA, not an app-generated anomaly alert. */
@Getter
@Setter
@NoArgsConstructor
@TableName("nta_service_alerts")
public class NtaServiceAlert {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String externalAlertId;
    private String headerText;
    private String descriptionText;
    private String cause;
    private String effect;
    private String severityLevel;
    private OffsetDateTime activeFrom;
    private OffsetDateTime activeUntil;
    private String externalRouteIds;
    private String externalStopIds;
    private String status;
    private OffsetDateTime lastSeenAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
