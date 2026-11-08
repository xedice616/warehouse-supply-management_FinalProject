package com.anbar.wms.security;

import com.anbar.wms.common.config.JpaAuditingConfig;
import java.util.Optional;
import org.springframework.data.domain.AuditorAware;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Supplies {@code created_by}/{@code updated_by}: the authenticated username, or {@code system} for work done
 * without a user (startup tasks, self-registration, schedulers).
 */
@Component(JpaAuditingConfig.AUDITOR_PROVIDER_BEAN)
public class SecurityAuditorAware implements AuditorAware<String> {

    private final AuthenticationTrustResolver trustResolver = new AuthenticationTrustResolverImpl();

    @Override
    public Optional<String> getCurrentAuditor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || trustResolver.isAnonymous(authentication)) {
            return Optional.of(JpaAuditingConfig.SYSTEM_AUDITOR);
        }
        return Optional.of(authentication.getName());
    }
}
