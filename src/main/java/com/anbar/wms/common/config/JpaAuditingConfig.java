package com.anbar.wms.common.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Fills {@code createdAt/createdBy/updatedAt/updatedBy} on {@link com.anbar.wms.common.entity.AuditableEntity}.
 * Timestamps come from the injected {@link Clock}; the auditor (username) comes from the security module's
 * {@code AuditorAware} bean named {@value #AUDITOR_PROVIDER_BEAN}.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaAuditing(auditorAwareRef = JpaAuditingConfig.AUDITOR_PROVIDER_BEAN,
        dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    public static final String AUDITOR_PROVIDER_BEAN = "auditorProvider";

    /** Auditor recorded when no user is authenticated (startup jobs, self-registration, schedulers). */
    public static final String SYSTEM_AUDITOR = "system";

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
