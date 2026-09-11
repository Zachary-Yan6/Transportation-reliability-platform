package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.StopDelayObservationResponse;
import com.zachary.transportation_reliability_platform.dto.TripDelayObservationResponse;
import com.zachary.transportation_reliability_platform.entity.TripStopDelayObservation;
import com.zachary.transportation_reliability_platform.mapper.TripStopDelayObservationMapper;
import com.zachary.transportation_reliability_platform.service.TripStopDelayObservationService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class TripStopDelayObservationServiceImpl
        extends ServiceImpl<
                TripStopDelayObservationMapper,
                TripStopDelayObservation
                >
        implements TripStopDelayObservationService {

    @Override
    public boolean existsByEventId(UUID eventId) {
        return exists(
                Wrappers.<TripStopDelayObservation>lambdaQuery()
                        .eq(TripStopDelayObservation::getEventId, eventId)
        );
    }

    @Override
    public boolean saveIfAbsent(TripStopDelayObservation observation) {
        return baseMapper.insertIfAbsent(observation) == 1;
    }

    @Override
    public List<TripDelayObservationResponse> findRecentByTripId(
            Long tripId,
            int limit
    ) {
        // Prevent invalid or excessively large queries.
        if (limit < 1 || limit > 100) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "limit must be between 1 and 100"
            );
        }

        return baseMapper.findRecentByTripId(tripId, limit);
    }

    @Override
    public List<StopDelayObservationResponse> findRecentByStopId(
            Long stopId,
            int limit
    ) {
        if (limit < 1 || limit > 100) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "limit must be between 1 and 100"
            );
        }

        return baseMapper.findRecentByStopId(stopId, limit);
    }
}
