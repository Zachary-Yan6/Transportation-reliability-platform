package com.zachary.transportation_reliability_platform.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.TripResponse;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.mapper.TripMapper;
import com.zachary.transportation_reliability_platform.service.TripReliabilityService;
import com.zachary.transportation_reliability_platform.service.TripService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TripServiceImpl
        extends ServiceImpl<TripMapper, Trip>
        implements TripService {

    @Override
    public List<TripResponse> findByRouteId(Long routeId) {
        List<Trip> trips = lambdaQuery()
                .eq(Trip::getRouteId, routeId)
                .orderByAsc(Trip::getId)
                .list();
        List<TripResponse> tripsResponse = trips.stream().map(trip -> {
            TripResponse tripResponse = new TripResponse();
            BeanUtils.copyProperties(trip, tripResponse);
            return tripResponse;
        }).collect(Collectors.toList());

        return tripsResponse;
    }

    @Override
    public Trip getRequiredById(Long id) {
        Trip trip = getById(id);

        if (trip == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Trip not found: " + id
            );
        }

        TripResponse tripResponse = new TripResponse();

        BeanUtils.copyProperties(trip, tripResponse);
        return trip;
    }
}