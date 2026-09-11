package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@TableName("trips")
public class Trip {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long feedVersionId;
    private Long routeId;
    private String externalTripId;
    private String serviceId;
    private String tripHeadsign;
    private Short directionId;
}