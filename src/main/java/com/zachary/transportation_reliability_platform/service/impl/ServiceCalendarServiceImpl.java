package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendar;
import com.zachary.transportation_reliability_platform.mapper.ServiceCalendarMapper;
import com.zachary.transportation_reliability_platform.service.ServiceCalendarService;
import org.springframework.stereotype.Service;

@Service
public class ServiceCalendarServiceImpl
        extends ServiceImpl<ServiceCalendarMapper, ServiceCalendar>
        implements ServiceCalendarService {
}