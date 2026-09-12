package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.auth.AuthResponse;
import com.zachary.transportation_reliability_platform.dto.auth.CurrentUserResponse;
import com.zachary.transportation_reliability_platform.dto.auth.LoginRequest;
import com.zachary.transportation_reliability_platform.dto.auth.RegisterRequest;
import com.zachary.transportation_reliability_platform.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Public login/registration endpoints plus the authenticated account profile. */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public CurrentUserResponse getCurrentUser(Authentication authentication) {
        return authService.getCurrentUser(authentication.getName());
    }
}
