package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.DashboardSummaryResponse;

public interface DashboardService {

    DashboardSummaryResponse getSummary(
            Long feedVersionId,
            int hours
    );
}