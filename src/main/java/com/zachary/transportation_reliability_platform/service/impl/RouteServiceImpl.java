package com.zachary.transportation_reliability_platform.service.impl;


import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.Route;
import com.zachary.transportation_reliability_platform.mapper.RouteMapper;
import com.zachary.transportation_reliability_platform.service.RouteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class RouteServiceImpl
        extends ServiceImpl<RouteMapper, Route>
        implements RouteService {

    @Override
    @Transactional(readOnly = true)
    public Route getRequiredById(Long id) {
        Route route = getById(id);

        if (route == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Route not found: " + id
            );
        }

        return route;
    }

    /**
     * Finds the internal route record matching the NTA route identifier used
     * by the realtime feed.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<Route> findByFeedVersionAndExternalRouteId(
            Long feedVersionId,
            String externalRouteId
    ) {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(Route::getFeedVersionId, feedVersionId)
                        .eq(Route::getExternalRouteId, externalRouteId)
                        .one()
        );
    }

}
