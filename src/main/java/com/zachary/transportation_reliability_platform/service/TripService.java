package com.zachary.transportation_reliability_platform.service;


import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.dto.TripResponse;
import com.zachary.transportation_reliability_platform.entity.Trip;

import java.util.List;

public interface TripService extends IService<Trip> {

    List<TripResponse> findByRouteId(Long routeId);

    Trip getRequiredById(Long id);
}