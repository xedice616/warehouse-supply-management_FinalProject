package com.anbar.wms.identity.service;

import com.anbar.wms.common.validation.PasswordPolicy;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.UserStatus;
import com.anbar.wms.identity.repository.AppUserRepository;
import com.anbar.wms.identity.repository.RoleRepository;
import com.anbar.wms.security.RoleCode;
import com.anbar.wms.security.config.WmsSecurityProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from {@code WMS_BOOTSTRAP_ADMIN_*} at startup, so a fresh installation can be
 * administered. Does nothing once any active ADMIN exists, or when the variables aren't set. Self-registration
 * never grants ADMIN, so this (or a DBA) is the only way to get the first one.
 */
@Service
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final WmsSecurityProperties.BootstrapAdmin settings;
    private final AppUserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;

    public AdminBootstrap(WmsSecurityProperties properties, AppUserRepository users, RoleRepository roles,
                          PasswordEncoder passwordEncoder) {
        this.settings = properties.bootstrapAdmin();
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!settings.isConfigured()) {
            return;
        }
        if (users.countByRoleAndStatus(RoleCode.ADMIN.name(), UserStatus.ACTIVE) > 0) {
            log.debug("An active administrator exists; bootstrap admin not needed");
            return;
        }
        PasswordPolicy.violation(settings.password()).ifPresent(problem -> {
            throw new IllegalStateException("WMS_BOOTSTRAP_ADMIN_PASSWORD " + problem);
        });
        String username = AppUser.normalizeUsername(settings.username());
        String email = AppUser.normalizeEmail(settings.email());
        if (users.existsByUsername(username) || users.existsByEmail(email)) {
            log.warn("Bootstrap admin not created: username '{}' or its email already belongs to another user",
                    username);
            return;
        }

        AppUser admin = new AppUser(username, email, passwordEncoder.encode(settings.password()), "Administrator");
        admin.replaceRoles(List.of(roles.findByCode(RoleCode.ADMIN.name()).orElseThrow()));
        users.save(admin);
        log.info("Created bootstrap administrator '{}'", username);
    }
}
