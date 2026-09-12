package com.zachary.transportation_reliability_platform.service.impl;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.common.exception.ErrorCode;
import com.zachary.transportation_reliability_platform.dto.auth.AuthResponse;
import com.zachary.transportation_reliability_platform.dto.auth.CurrentUserResponse;
import com.zachary.transportation_reliability_platform.dto.auth.LoginRequest;
import com.zachary.transportation_reliability_platform.dto.auth.RegisterRequest;
import com.zachary.transportation_reliability_platform.entity.AppUser;
import com.zachary.transportation_reliability_platform.security.JwtService;
import com.zachary.transportation_reliability_platform.service.AppUserService;
import com.zachary.transportation_reliability_platform.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;

/** Performs password authentication and issues a short-lived JWT on success. */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private static final String USER_ROLE = "USER";

    private final AppUserService appUserService;
    private final AuthenticationManager authenticationManager;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = normaliseEmail(request.email());
        if (appUserService.findByEmail(email).isPresent()) {
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        AppUser account = new AppUser();
        account.setEmail(email);
        account.setPasswordHash(passwordEncoder.encode(request.password()));
        // Registration never accepts a role from the browser. Administrator
        // accounts are provisioned by the protected bootstrap configuration.
        account.setRole(USER_ROLE);
        account.setEnabled(true);
        try {
            appUserService.save(account);
        } catch (DataIntegrityViolationException exception) {
            // The pre-insert lookup is friendly, while this handles two
            // simultaneous registrations racing for the unique email index.
            throw new BusinessException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }

        return responseFor(account);
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        String email = normaliseEmail(request.email());
        try {
            authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            email,
                            request.password()
                    )
            );
        } catch (AuthenticationException exception) {
            // Never reveal whether the email or password was incorrect.
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS);
        }

        AppUser account = appUserService.findByEmail(email)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        return responseFor(account);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrentUserResponse getCurrentUser(String email) {
        AppUser accounts = appUserService.findByEmail(normaliseEmail(email))
                .filter(account -> Boolean.TRUE.equals(account.getEnabled()))
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_CREDENTIALS));
        return toCurrentUser(accounts);
    }

    private AuthResponse responseFor(AppUser account) {
        String token = jwtService.issue(account);
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                jwtService.getExpiry(token),
                ZoneOffset.UTC
        );
        return new AuthResponse(token, "Bearer", expiresAt, toCurrentUser(account));
    }

    private CurrentUserResponse toCurrentUser(AppUser account) {
        return new CurrentUserResponse(
                account.getId(),
                account.getEmail(),
                account.getRole()
        );
    }

    private String normaliseEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
