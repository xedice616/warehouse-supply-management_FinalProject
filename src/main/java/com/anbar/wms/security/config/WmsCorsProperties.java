package com.anbar.wms.security.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-origin settings under {@code wms.cors.*}. Secure by default: with no {@code allowedOrigins} configured,
 * no cross-origin browser request is permitted (same-origin and non-browser clients such as curl are unaffected).
 * Set {@code WMS_CORS_ALLOWED_ORIGINS} to a comma-separated list of exact origins to allow a web front-end.
 *
 * @param allowedOrigins exact origins (scheme + host + port), e.g. {@code https://app.example.com}
 * @param allowedMethods HTTP methods a browser may use cross-origin
 * @param allowedHeaders request headers a browser may send cross-origin
 * @param exposedHeaders response headers a browser may read
 * @param allowCredentials whether cookies/credentials may be sent; keep false for a bearer-token API
 * @param maxAge how long a browser may cache the preflight response
 */
@ConfigurationProperties(prefix = "wms.cors")
public record WmsCorsProperties(
        List<String> allowedOrigins,
        List<String> allowedMethods,
        List<String> allowedHeaders,
        List<String> exposedHeaders,
        boolean allowCredentials,
        Duration maxAge) {

    public WmsCorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        allowedMethods = emptyToDefault(allowedMethods, List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        allowedHeaders = emptyToDefault(allowedHeaders, List.of("Authorization", "Content-Type", "Idempotency-Key"));
        exposedHeaders = exposedHeaders == null ? List.of("Location") : List.copyOf(exposedHeaders);
        maxAge = maxAge == null ? Duration.ofHours(1) : maxAge;
    }

    private static List<String> emptyToDefault(List<String> value, List<String> fallback) {
        return value == null || value.isEmpty() ? fallback : List.copyOf(value);
    }
}
