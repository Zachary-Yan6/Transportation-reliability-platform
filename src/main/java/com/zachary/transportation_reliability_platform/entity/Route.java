package com.zachary.transportation_reliability_platform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@TableName("routes")
@Data
public class Route {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private Long feedVersionId;
    private String externalRouteId;
    private String agencyId;
    private String shortName;
    private String longName;
    private Short routeType;


}