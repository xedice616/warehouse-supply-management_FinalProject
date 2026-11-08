package com.anbar.wms.security.config;

import com.anbar.wms.security.AuthenticatedUserResolver;
import com.anbar.wms.security.jwt.JwtAuthenticationFilter;
import com.anbar.wms.security.jwt.JwtService;
import com.anbar.wms.security.web.ProblemAccessDeniedHandler;
import com.anbar.wms.security.web.ProblemAuthenticationEntryPoint;
import java.util.Map;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless JWT security for the REST API.
 *
 * <ul>
 *   <li>Public: register, login, health/info probes, OpenAPI docs and Swagger UI (disabled in prod), {@code /error}.</li>
 *   <li>Everything else requires a valid bearer token. Role checks are declared per endpoint with
 *       {@code @PreAuthorize} (method security).</li>
 *   <li>No sessions, cookies, form login or HTTP Basic. CSRF protection is off because the API is authenticated
 *       only by the {@code Authorization} header, which a browser never attaches on its own.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties({WmsSecurityProperties.class, WmsCorsProperties.class})
public class SecurityConfig {

    static final String[] PUBLIC_DOCS = {"/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**"};

    @Bean
    public SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                                      JwtService jwtService,
                                                      AuthenticatedUserResolver userResolver,
                                                      ProblemAuthenticationEntryPoint authenticationEntryPoint,
                                                      ProblemAccessDeniedHandler accessDeniedHandler,
                                                      CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**", "/actuator/info")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, PUBLIC_DOCS).permitAll()
                        .requestMatchers("/error").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userResolver),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * BCrypt with a configurable cost, wrapped in a {@link DelegatingPasswordEncoder} so stored hashes carry their
     * algorithm id ({@code {bcrypt}$2a$12$...}) and the algorithm can be upgraded later without a big-bang migration.
     */
    @Bean
    public PasswordEncoder passwordEncoder(WmsSecurityProperties properties) {
        String bcrypt = "bcrypt";
        return new DelegatingPasswordEncoder(bcrypt,
                Map.of(bcrypt, new BCryptPasswordEncoder(properties.bcryptStrength())));
    }

    /**
     * CORS for the API. With no configured origins the configuration allows none, so cross-origin browser
     * requests are rejected by default; same-origin and non-browser clients are unaffected. Origins are
     * configured through {@code WMS_CORS_ALLOWED_ORIGINS}.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(WmsCorsProperties properties) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(properties.allowedOrigins());
        config.setAllowedMethods(properties.allowedMethods());
        config.setAllowedHeaders(properties.allowedHeaders());
        config.setExposedHeaders(properties.exposedHeaders());
        config.setAllowCredentials(properties.allowCredentials());
        config.setMaxAge(properties.maxAge());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
