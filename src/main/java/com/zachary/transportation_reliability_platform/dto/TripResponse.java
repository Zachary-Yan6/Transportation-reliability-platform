package com.zachary.transportation_reliability_platform.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class TripResponse {
    private Long id;

    private Long feedVersionId;

    private Long routeId;

    private String externalTripId;

    private String serviceId;

    private String tripHeadsign;

    private Short directionId;
}
