package com.zachary.transportation_reliability_platform.security;

import com.zachary.transportation_reliability_platform.entity.AppUser;
import com.zachary.transportation_reliability_platform.service.AppUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/** Adapts an application account to Spring Security's password authentication. */
@Service
@RequiredArgsConstructor
public class AccountUserDetailsService implements UserDetailsService {

    private final AppUserService appUserService;

    @Override
    public UserDetails loadUserByUsername(String email) {
        AppUser account = appUserService.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("Account not found"));

        return User.withUsername(account.getEmail())
                .password(account.getPasswordHash())
                .roles(account.getRole())
                .disabled(!Boolean.TRUE.equals(account.getEnabled()))
                .build();
    }
}
