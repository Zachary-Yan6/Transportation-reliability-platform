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
@TableName("stop_times")
public class StopTime {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long tripId;
    private Long stopId;
    private Integer stopSequence;
    private Integer arrivalSeconds;
    private Integer departureSeconds;
    private Short pickupType;
    private Short dropOffType;
}