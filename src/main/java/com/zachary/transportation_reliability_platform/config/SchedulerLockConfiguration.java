package com.zachary.transportation_reliability_platform.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Coordinates scheduled work across every application instance through the
 * shared PostgreSQL database. Database time is deliberately used so server
 * clock drift cannot make two instances believe that the same lock is free.
 */
@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT15M")
public class SchedulerLockConfiguration {

    @Bean
    public LockProvider schedulerLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
                JdbcTemplateLockProvider.Configuration.builder()
                        .withJdbcTemplate(new JdbcTemplate(dataSource))
                        .usingDbTime()
                        .build()
        );
    }
}
