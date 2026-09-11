package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.dto.NtaServiceAlertResponse;
import com.zachary.transportation_reliability_platform.entity.NtaServiceAlert;

import java.util.List;

public interface NtaServiceAlertService extends IService<NtaServiceAlert> {
    List<NtaServiceAlertResponse> findAlerts(boolean activeOnly, int limit);
}
