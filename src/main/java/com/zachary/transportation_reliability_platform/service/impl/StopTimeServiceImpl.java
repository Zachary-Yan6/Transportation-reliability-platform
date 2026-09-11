package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.dto.StopTimeScheduleResponse;
import com.zachary.transportation_reliability_platform.entity.StopTime;
import com.zachary.transportation_reliability_platform.mapper.StopTimeMapper;
import com.zachary.transportation_reliability_platform.service.StopTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StopTimeServiceImpl
        extends ServiceImpl<StopTimeMapper, StopTime>
        implements StopTimeService {

    private final StopTimeMapper stopTimeMapper;

    @Override
    public List<StopTime> findByTripId(Long tripId) {
        return lambdaQuery()
                .eq(StopTime::getTripId, tripId)
                .orderByAsc(StopTime::getStopSequence)
                .list();
    }

    @Override
    public boolean existsForFeedVersionId(Long feedVersionId) {
        return stopTimeMapper.existsForFeedVersionId(feedVersionId);
    }

    @Override
    @Transactional(readOnly = true)
    /**
     * search all stop information(sequence, id, name, arrival, departure) in a trip
     */
    public List<StopTimeScheduleResponse> getScheduleByTripId(Long tripId) {
        return stopTimeMapper.findScheduleByTripId(tripId)
                .stream()
                .map(row -> new StopTimeScheduleResponse(
                        row.getStopSequence(),
                        row.getExternalStopId(),
                        row.getStopName(),
                        formatGtfsTime(row.getArrivalSeconds()),
                        formatGtfsTime(row.getDepartureSeconds())
                ))
                .toList();
    }

    private String formatGtfsTime(Integer totalSeconds) {
        if (totalSeconds == null) {
            return null;
        }

        int hours = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }
}