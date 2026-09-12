package com.zachary.transportation_reliability_platform.security;

import com.zachary.transportation_reliability_platform.entity.AppUser;
import com.zachary.transportation_reliability_platform.service.AppUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/** Creates one administrator only when secure bootstrap environment values exist. */
@Slf4j
@Component
@RequiredArgsConstructor
public class BootstrapAdminInitializer implements ApplicationRunner {

    private final SecurityProperties properties;
    private final AppUserService appUserService;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        String configuredEmail = properties.bootstrapAdminEmail();
        String configuredPassword = properties.bootstrapAdminPassword();
        if (configuredEmail == null || configuredEmail.isBlank()
                || configuredPassword == null || configuredPassword.isBlank()) {
            log.info("No bootstrap administrator configured; skipping admin account creation");
            return;
        }

        String email = configuredEmail.trim().toLowerCase(Locale.ROOT);
        if (appUserService.findByEmail(email).isPresent()) {
            return;
        }

        AppUser administrator = new AppUser();
        administrator.setEmail(email);
        administrator.setPasswordHash(passwordEncoder.encode(configuredPassword));
        administrator.setRole("ADMIN");
        administrator.setEnabled(true);
        appUserService.save(administrator);
        log.info("Created configured bootstrap administrator account for {}", email);
    }
}
