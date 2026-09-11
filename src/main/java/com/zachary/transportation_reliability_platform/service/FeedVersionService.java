package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;

import java.util.Optional;

public interface FeedVersionService extends IService<FeedVersion> {

    Optional<FeedVersion> findByChecksum(String checksum);

    Optional<FeedVersion> findActive();

    FeedVersion activate(Long id);

    FeedVersion getRequiredById(Long id);
}
