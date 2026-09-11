package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.dto.StopTimeScheduleResponse;
import com.zachary.transportation_reliability_platform.entity.StopTime;

import java.util.List;

public interface StopTimeService extends IService<StopTime> {

    List<StopTime> findByTripId(Long tripId);

    boolean existsForFeedVersionId(Long feedVersionId);

    List<StopTimeScheduleResponse> getScheduleByTripId(Long tripId);
}