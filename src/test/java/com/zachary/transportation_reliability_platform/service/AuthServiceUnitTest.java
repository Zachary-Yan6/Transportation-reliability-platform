package com.zachary.transportation_reliability_platform.service;

import com.zachary.transportation_reliability_platform.common.exception.BusinessException;
import com.zachary.transportation_reliability_platform.dto.auth.CurrentUserResponse;
import com.zachary.transportation_reliability_platform.dto.auth.LoginRequest;
import com.zachary.transportation_reliability_platform.dto.auth.RegisterRequest;
import com.zachary.transportation_reliability_platform.entity.AppUser;
import com.zachary.transportation_reliability_platform.security.JwtService;
import com.zachary.transportation_reliability_platform.service.impl.AuthServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Covers account creation, credential checks, and the safe profile response. */
class AuthServiceUnitTest {

    @Test
    void registrationNormalisesEmailCreatesOnlyAUserAndIssuesJwt() {
        Dependencies dependencies = dependencies();
        when(dependencies.appUserService.findByEmail("user@example.com"))
                .thenReturn(Optional.empty());
        when(dependencies.passwordEncoder.encode("correct-password"))
                .thenReturn("bcrypt-hash");
        when(dependencies.jwtService.issue(any())).thenReturn("signed-token");
        when(dependencies.jwtService.getExpiry("signed-token"))
                .thenReturn(Instant.parse("2026-09-12T12:00:00Z"));

        var response = dependencies.service().register(
                new RegisterRequest(" User@Example.COM ", "correct-password")
        );

        assertThat(response.accessToken()).isEqualTo("signed-token");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.user().email()).isEqualTo("user@example.com");
        assertThat(response.user().role()).isEqualTo("USER");
        verify(dependencies.appUserService).save(any(AppUser.class));
    }

    @Test
    void registrationRejectsAnExistingEmail() {
        Dependencies dependencies = dependencies();
        when(dependencies.appUserService.findByEmail("user@example.com"))
                .thenReturn(Optional.of(account("user@example.com", "USER", true)));

        assertThatThrownBy(() -> dependencies.service().register(
                new RegisterRequest("user@example.com", "correct-password")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("An account with this email already exists");
    }

    @Test
    void registrationConvertsAConcurrentUniqueKeyFailureToTheSameSafeResponse() {
        Dependencies dependencies = dependencies();
        when(dependencies.appUserService.findByEmail("user@example.com"))
                .thenReturn(Optional.empty());
        when(dependencies.passwordEncoder.encode("correct-password"))
                .thenReturn("bcrypt-hash");
        when(dependencies.appUserService.save(any(AppUser.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate email"));

        assertThatThrownBy(() -> dependencies.service().register(
                new RegisterRequest("user@example.com", "correct-password")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("An account with this email already exists");
    }

    @Test
    void loginAuthenticatesThenReturnsTheAccountAndJwt() {
        Dependencies dependencies = dependencies();
        AppUser account = account("user@example.com", "USER", true);
        when(dependencies.authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.authenticated(
                        "user@example.com", null, java.util.List.of()
                ));
        when(dependencies.appUserService.findByEmail("user@example.com"))
                .thenReturn(Optional.of(account));
        when(dependencies.jwtService.issue(account)).thenReturn("token");
        when(dependencies.jwtService.getExpiry("token"))
                .thenReturn(Instant.parse("2026-09-12T12:00:00Z"));

        var response = dependencies.service().login(
                new LoginRequest("USER@example.com", "correct-password")
        );

        assertThat(response.user().role()).isEqualTo("USER");
        verify(dependencies.authenticationManager).authenticate(any());
    }

    @Test
    void failedOrMissingLoginNeverDisclosesWhichCredentialWasWrong() {
        Dependencies failedAuthentication = dependencies();
        when(failedAuthentication.authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("bad"));

        assertThatThrownBy(() -> failedAuthentication.service().login(
                new LoginRequest("user@example.com", "wrong-password")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Email or password is incorrect");

        Dependencies missingAccount = dependencies();
        when(missingAccount.authenticationManager.authenticate(any()))
                .thenReturn(UsernamePasswordAuthenticationToken.unauthenticated(
                        "user@example.com", "ignored"
                ));
        when(missingAccount.appUserService.findByEmail("user@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> missingAccount.service().login(
                new LoginRequest("user@example.com", "correct-password")
        )).isInstanceOf(BusinessException.class)
                .hasMessage("Email or password is incorrect");
    }

    @Test
    void currentUserRequiresAnEnabledExistingAccount() {
        Dependencies dependencies = dependencies();
        AppUser administrator = account("admin@example.com", "ADMIN", true);
        when(dependencies.appUserService.findByEmail("admin@example.com"))
                .thenReturn(Optional.of(administrator));

        assertThat(dependencies.service().getCurrentUser("ADMIN@example.com"))
                .extracting(CurrentUserResponse::email, CurrentUserResponse::role)
                .containsExactly("admin@example.com", "ADMIN");

        when(dependencies.appUserService.findByEmail("disabled@example.com"))
                .thenReturn(Optional.of(account("disabled@example.com", "USER", false)));
        assertThatThrownBy(() -> dependencies.service().getCurrentUser("disabled@example.com"))
                .isInstanceOf(BusinessException.class);
    }

    private static AppUser account(String email, String role, boolean enabled) {
        AppUser account = new AppUser();
        account.setId(7L);
        account.setEmail(email);
        account.setPasswordHash("bcrypt-hash");
        account.setRole(role);
        account.setEnabled(enabled);
        return account;
    }

    private static Dependencies dependencies() {
        AppUserService appUserService = mock(AppUserService.class);
        AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtService jwtService = mock(JwtService.class);
        return new Dependencies(
                appUserService,
                authenticationManager,
                passwordEncoder,
                jwtService
        );
    }

    private record Dependencies(
            AppUserService appUserService,
            AuthenticationManager authenticationManager,
            PasswordEncoder passwordEncoder,
            JwtService jwtService
    ) {
        private AuthServiceImpl service() {
            return new AuthServiceImpl(
                    appUserService,
                    authenticationManager,
                    passwordEncoder,
                    jwtService
            );
        }
    }
}
