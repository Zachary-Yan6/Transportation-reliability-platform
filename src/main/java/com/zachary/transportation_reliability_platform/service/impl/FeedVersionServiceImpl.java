package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.entity.FeedVersion;
import com.zachary.transportation_reliability_platform.mapper.FeedVersionMapper;
import com.zachary.transportation_reliability_platform.service.FeedVersionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class FeedVersionServiceImpl
        extends ServiceImpl<FeedVersionMapper, FeedVersion>
        implements FeedVersionService {

    @Override
    public Optional<FeedVersion> findByChecksum(String checksum) {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(FeedVersion::getChecksum, checksum)
                        .one()
        );
    }

    @Override
    public Optional<FeedVersion> findActive() {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(FeedVersion::getLifecycleStatus, "ACTIVE")
                        .one()
        );
    }

    /**
     * Makes one fully imported feed the only active matching reference. Older
     * feeds remain available for historical observations and are not deleted.
     */
    @Override
    @Transactional
    public FeedVersion activate(Long id) {
        FeedVersion target = getRequiredById(id);

        lambdaUpdate()
                .set(FeedVersion::getLifecycleStatus, "SUPERSEDED")
                .eq(FeedVersion::getLifecycleStatus, "ACTIVE")
                .ne(FeedVersion::getId, id)
                .update();

        target.setLifecycleStatus("ACTIVE");
        updateById(target);
        return target;
    }

    @Override
    public FeedVersion getRequiredById(Long id) {
        FeedVersion feedVersion = getById(id);

        if (feedVersion == null) {
            throw new BusinessException(
                    ErrorCode.RESOURCE_NOT_FOUND,
                    "Feed version not found: " + id
            );
        }

        return feedVersion;
    }
}
