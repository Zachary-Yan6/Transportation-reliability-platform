package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@TableName("stops")
public class Stop {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long feedVersionId;
    private String externalStopId;
    private String stopName;
    private BigDecimal latitude;
    private BigDecimal longitude;
}