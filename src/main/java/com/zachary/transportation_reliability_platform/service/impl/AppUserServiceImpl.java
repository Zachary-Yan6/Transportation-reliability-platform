package com.zachary.transportation_reliability_platform.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zachary.transportation_reliability_platform.entity.AppUser;
import com.zachary.transportation_reliability_platform.mapper.AppUserMapper;
import com.zachary.transportation_reliability_platform.service.AppUserService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Database access for case-normalised application account emails. */
@Service
public class AppUserServiceImpl
        extends ServiceImpl<AppUserMapper, AppUser>
        implements AppUserService {

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUser> findByEmail(String email) {
        return Optional.ofNullable(
                lambdaQuery()
                        .eq(AppUser::getEmail, email)
                        .one()
        );
    }
}
