package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.Stop;
import com.zachary.transportation_reliability_platform.mapper.StopMapper;
import com.zachary.transportation_reliability_platform.service.StopService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StopServiceImpl
        extends ServiceImpl<StopMapper, Stop>
        implements StopService {

    @Override
    public List<Stop> searchByName(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }

        return lambdaQuery()
                .like(Stop::getStopName, keyword.trim())
                .orderByAsc(Stop::getStopName)
                .list();
    }

    @Override
    public Stop getRequiredById(Long id) {
        Stop stop = getById(id);

        if (stop == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Stop not found: " + id
            );
        }

        return stop;
    }
}