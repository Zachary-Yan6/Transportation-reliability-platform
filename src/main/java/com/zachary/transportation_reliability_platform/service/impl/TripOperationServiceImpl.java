package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zachary.transportation_reliability_platform.dto.TripOperationStatusResponse;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendar;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendarDate;
import com.zachary.transportation_reliability_platform.entity.Trip;
import com.zachary.transportation_reliability_platform.service.ServiceCalendarDateService;
import com.zachary.transportation_reliability_platform.service.ServiceCalendarService;
import com.zachary.transportation_reliability_platform.service.TripOperationService;
import com.zachary.transportation_reliability_platform.service.TripService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;

@Service
@RequiredArgsConstructor
public class TripOperationServiceImpl implements TripOperationService {

    private final TripService tripService;
    private final ServiceCalendarService serviceCalendarService;
    private final ServiceCalendarDateService serviceCalendarDateService;

    @Override
    @Transactional(readOnly = true)
    public TripOperationStatusResponse getOperationStatus(
            Long tripId,
            LocalDate serviceDate
    ) {
        // actual trip
        Trip trip = tripService.getRequiredById(tripId);

        // Is there any exception for this trip at a specific day?
        ServiceCalendarDate calendarDateException =
                serviceCalendarDateService.getOne(
                        Wrappers.<ServiceCalendarDate>lambdaQuery()
                                .eq(ServiceCalendarDate::getFeedVersionId,
                                        trip.getFeedVersionId())
                                .eq(ServiceCalendarDate::getExternalServiceId,
                                        trip.getServiceId())
                                .eq(ServiceCalendarDate::getServiceDate,
                                        serviceDate),
                        false
                );

        // has running exception
        if (calendarDateException != null) {
            // 1 : added at that day
            // 2: removed at that day
            boolean isAddedService =
                    calendarDateException.getExceptionType() == 1;

            return response(
                    trip,
                    serviceDate,
                    isAddedService,
                    isAddedService
                            ? "ADDED_BY_CALENDAR_DATE"
                            : "REMOVED_BY_CALENDAR_DATE"
            );
        }

        // no exception at a specific day
        // query normal operation
        ServiceCalendar calendar = serviceCalendarService.getOne(
                Wrappers.<ServiceCalendar>lambdaQuery()
                        .eq(ServiceCalendar::getFeedVersionId,
                                trip.getFeedVersionId())
                        .eq(ServiceCalendar::getExternalServiceId,
                                trip.getServiceId()),
                false
        );

        // no operation at a specific day
        if (calendar == null) {
            return response(
                    trip,
                    serviceDate,
                    false,
                    "NO_CALENDAR_RULE"
            );
        }

        // out of time range
        if (serviceDate.isBefore(calendar.getStartDate())
                || serviceDate.isAfter(calendar.getEndDate())) {
            return response(
                    trip,
                    serviceDate,
                    false,
                    "OUTSIDE_CALENDAR_RANGE"
            );
        }

        // check some day in a week would operate
        boolean running = runsOnDayOfWeek(calendar, serviceDate.getDayOfWeek());

        return response(
                trip,
                serviceDate,
                running,
                running
                        ? "REGULAR_SCHEDULE"
                        : "NOT_SCHEDULED_ON_WEEKDAY"
        );
    }

    private boolean runsOnDayOfWeek(
            ServiceCalendar calendar,
            DayOfWeek dayOfWeek
    ) {
        return switch (dayOfWeek) {
            case MONDAY -> calendar.getMonday();
            case TUESDAY -> calendar.getTuesday();
            case WEDNESDAY -> calendar.getWednesday();
            case THURSDAY -> calendar.getThursday();
            case FRIDAY -> calendar.getFriday();
            case SATURDAY -> calendar.getSaturday();
            case SUNDAY -> calendar.getSunday();
        };
    }

    private TripOperationStatusResponse response(
            Trip trip,
            LocalDate serviceDate,
            boolean running,
            String reason
    ) {
        return new TripOperationStatusResponse(
                trip.getId(),
                trip.getExternalTripId(),
                serviceDate,
                running,
                reason
        );
    }
}