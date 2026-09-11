package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.entity.ServiceCalendarDate;
import com.zachary.transportation_reliability_platform.mapper.ServiceCalendarDateMapper;
import com.zachary.transportation_reliability_platform.service.ServiceCalendarDateService;
import org.springframework.stereotype.Service;

@Service
public class ServiceCalendarDateServiceImpl
        extends ServiceImpl<ServiceCalendarDateMapper, ServiceCalendarDate>
        implements ServiceCalendarDateService {
}