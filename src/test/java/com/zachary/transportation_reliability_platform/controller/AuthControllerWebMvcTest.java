package com.zachary.transportation_reliability_platform.controller;

import com.zachary.transportation_reliability_platform.dto.auth.AuthResponse;
import com.zachary.transportation_reliability_platform.dto.auth.CurrentUserResponse;
import com.zachary.transportation_reliability_platform.service.AuthService;
import com.zachary.transportation_reliability_platform.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** MVC contracts for login, public registration, and the current profile. */
@WebMvcTest(
        controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtAuthenticationFilter.class
        )
)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @Test
    void registrationAndLoginReturnTheTokenPayload() throws Exception {
        when(authService.register(any())).thenReturn(authResponse("user@example.com", "USER"));
        when(authService.login(any())).thenReturn(authResponse("admin@example.com", "ADMIN"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"user@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("USER"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@example.com\",\"password\":\"password123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("token"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));

        verify(authService).register(any());
        verify(authService).login(any());
    }

    @Test
    void currentProfileUsesTheAuthenticatedPrincipalAndInvalidBodiesAreRejected() throws Exception {
        when(authService.getCurrentUser("user@example.com"))
                .thenReturn(new CurrentUserResponse(7L, "user@example.com", "USER"));

        mockMvc.perform(get("/api/v1/auth/me")
                        .principal(UsernamePasswordAuthenticationToken.authenticated(
                                "user@example.com", null, java.util.List.of()
                        )))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("user@example.com"));
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest());

        verify(authService).getCurrentUser("user@example.com");
    }

    private static AuthResponse authResponse(String email, String role) {
        return new AuthResponse(
                "token",
                "Bearer",
                OffsetDateTime.parse("2026-09-12T12:00:00Z"),
                new CurrentUserResponse(7L, email, role)
        );
    }
}
