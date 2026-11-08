package com.anbar.wms.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Security settings under {@code wms.security.*}. The JWT secret has no default and must come from the
 * environment ({@code WMS_JWT_SECRET}); the application refuses to start without it.
 *
 * @param bcryptStrength BCrypt cost factor (log2 rounds); 12 in production, lower only in tests
 */
@Validated
@ConfigurationProperties(prefix = "wms.security")
public record WmsSecurityProperties(
        @Valid @NotNull Jwt jwt,
        @Min(4) @Max(16) int bcryptStrength,
        @Valid @NotNull Lockout lockout,
        @Valid @NotNull BootstrapAdmin bootstrapAdmin) {

    /**
     * @param secret         Base64-encoded HMAC key of at least 256 bits
     * @param issuer         {@code iss} claim written and required on every token
     * @param audience       {@code aud} claim written and required on every token
     * @param accessTokenTtl lifetime of an access token
     * @param clockSkew      tolerance when checking {@code exp}/{@code iat} against the server clock
     */
    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            @NotNull Duration accessTokenTtl,
            @NotNull Duration clockSkew) {
    }

    /**
     * @param maxFailedAttempts consecutive wrong passwords that lock the account
     * @param duration          how long the account stays locked
     */
    public record Lockout(@Min(1) int maxFailedAttempts, @NotNull Duration duration) {
    }

    /**
     * First administrator, created at startup only if all three values are set and no active ADMIN exists.
     * Lets a fresh installation be administered without any user having ADMIN.
     */
    public record BootstrapAdmin(String username, String email, String password) {

        public boolean isConfigured() {
            return notBlank(username) && notBlank(email) && notBlank(password);
        }

        private static boolean notBlank(String value) {
            return value != null && !value.isBlank();
        }

        @Override
        public String toString() {
            return "BootstrapAdmin[username=" + username + ", email=" + email + ", password=****]";
        }
    }
}
