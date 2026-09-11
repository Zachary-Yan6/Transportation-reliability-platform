package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A persisted operational alert created from an anomaly detection result.
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("service_alerts")
public class ServiceAlert {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long routeId;
    private String alertType;
    private String severity;
    private String status;
    private String message;

    private OffsetDateTime firstDetectedAt;
    private OffsetDateTime lastDetectedAt;
    private OffsetDateTime resolvedAt;
}
