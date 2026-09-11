package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.entity.Stop;

import java.util.List;

public interface StopService extends IService<Stop> {

    List<Stop> searchByName(String keyword);

    Stop getRequiredById(Long id);
}