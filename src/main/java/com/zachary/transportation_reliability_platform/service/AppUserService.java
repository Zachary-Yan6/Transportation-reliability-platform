package com.zachary.transportation_reliability_platform.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.zachary.transportation_reliability_platform.entity.AppUser;

import java.util.Optional;

/** Provides account lookup without exposing password hashes outside auth code. */
public interface AppUserService extends IService<AppUser> {

    Optional<AppUser> findByEmail(String email);
}
