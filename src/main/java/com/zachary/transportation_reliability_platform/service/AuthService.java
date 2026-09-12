package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.dto.auth.AuthResponse;
import com.zachary.transportation_reliability_platform.dto.auth.CurrentUserResponse;
import com.zachary.transportation_reliability_platform.dto.auth.LoginRequest;
import com.zachary.transportation_reliability_platform.dto.auth.RegisterRequest;

/** Application-level login and self-registration use cases. */
public interface AuthService {

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    CurrentUserResponse getCurrentUser(String email);
}
