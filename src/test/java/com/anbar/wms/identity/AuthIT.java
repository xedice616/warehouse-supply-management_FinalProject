package com.anbar.wms.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.security.config.WmsSecurityProperties;
import com.anbar.wms.security.jwt.JwtService;
import static com.anbar.wms.support.ProblemMatchers.problem;

import com.anbar.wms.support.AbstractIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

/** Registration, login and token handling over HTTP, against a real database. */
class AuthIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WmsSecurityProperties securityProperties;

    // ================================================================== registration

    @Nested
    class Registration {

        @Test
        void succeedsWithUserRoleAndNoSecretsInTheResponse() throws Exception {
            String username = unique("Reg");

            register(username, username + "@Example.test", USER_PASSWORD)
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").isNumber())
                    .andExpect(jsonPath("$.username").value(username.toLowerCase()))
                    .andExpect(jsonPath("$.email").value(username.toLowerCase() + "@example.test"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"))
                    .andExpect(jsonPath("$.roles", contains("USER")))
                    .andExpect(jsonPath("$.password").doesNotExist())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist())
                    .andExpect(content().string(not(containsString(USER_PASSWORD))))
                    .andExpect(content().string(not(containsString("$2a$"))));

            String storedHash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE username = ?",
                    String.class, username.toLowerCase());
            assertThat(storedHash).startsWith("{bcrypt}$2a$").doesNotContain(USER_PASSWORD);
        }

        @Test
        void duplicateEmailIgnoringCaseIsConflict() throws Exception {
            String username = unique("dup");
            registerUser(username);

            register(unique("other"), username.toUpperCase() + "@EXAMPLE.TEST", USER_PASSWORD)
                    .andExpect(problem(409, "EMAIL_ALREADY_REGISTERED"));
        }

        @Test
        void duplicateUsernameIsConflict() throws Exception {
            String username = unique("same");
            registerUser(username);

            register(username, unique("fresh") + "@example.test", USER_PASSWORD)
                    .andExpect(problem(409, "USERNAME_ALREADY_TAKEN"));
        }

        @Test
        void weakPasswordIsValidationError() throws Exception {
            register(unique("weak"), unique("weak") + "@example.test", "password")
                    .andExpect(problem(400, "VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("password"));
        }

        @Test
        void cannotSelfAssignRoles() throws Exception {
            String username = unique("sneaky");
            postJson("/api/v1/auth/register", """
                    {"username": "%s", "email": "%s@example.test", "password": "%s", "fullName": "S",
                     "roles": ["ADMIN"]}""".formatted(username, username, USER_PASSWORD))
                    .andExpect(problem(400, "MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Unknown property 'roles'"));
        }
    }

    // ================================================================== login

    @Nested
    class Login {

        @Test
        void succeedsWithUsernameOrEmailAndTheTokenWorks() throws Exception {
            String username = unique("login");
            registerUser(username);

            login(username.toUpperCase(), USER_PASSWORD)
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andExpect(jsonPath("$.expiresIn").value(900))
                    .andExpect(jsonPath("$.accessToken").isString())
                    .andExpect(jsonPath("$.expiresAt").isString());

            String token = tokenFor(username + "@example.test", USER_PASSWORD);
            me(token)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.username").value(username))
                    .andExpect(jsonPath("$.roles", contains("USER")))
                    .andExpect(jsonPath("$.lastLoginAt").isString())
                    .andExpect(jsonPath("$.passwordHash").doesNotExist())
                    .andExpect(content().string(not(containsString("$2a$"))));
        }

        @Test
        void wrongPasswordAndUnknownUserGetTheSameAnswer() throws Exception {
            String username = unique("creds");
            registerUser(username);

            login(username, "Wrong-password-1").andExpect(problem(401, "INVALID_CREDENTIALS"));
            login(unique("nobody"), USER_PASSWORD).andExpect(problem(401, "INVALID_CREDENTIALS"));
        }

        @Test
        void fiveWrongPasswordsLockTheAccount() throws Exception {
            String username = unique("brute");
            registerUser(username);

            for (int attempt = 0; attempt < securityProperties.lockout().maxFailedAttempts(); attempt++) {
                login(username, "Wrong-password-" + attempt).andExpect(problem(401, "INVALID_CREDENTIALS"));
            }

            login(username, USER_PASSWORD).andExpect(problem(401, "ACCOUNT_LOCKED"));
        }

        @Test
        void disabledAccountCannotLogIn() throws Exception {
            String username = unique("gone");
            registerUser(username);
            jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE username = ?", username);

            login(username, USER_PASSWORD).andExpect(problem(401, "ACCOUNT_DISABLED"));
        }
    }

    // ================================================================== tokens

    @Nested
    class Tokens {

        @Test
        void missingTokenIsAuthenticationRequired() throws Exception {
            mockMvc.perform(get("/api/v1/auth/me"))
                    .andExpect(problem(401, "AUTHENTICATION_REQUIRED"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"wms\""));
        }

        @Test
        void malformedTokenIsInvalid() throws Exception {
            me("not.a.jwt")
                    .andExpect(problem(401, "INVALID_TOKEN"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")));
        }

        @Test
        void tokenSignedWithAnotherKeyIsInvalid() throws Exception {
            long userId = registerUser(unique("forged"));
            String forged = jwtService(Base64.getEncoder().encodeToString(
                    "attacker-controlled-secret-of-sufficient-length!!".getBytes(StandardCharsets.UTF_8)), Instant.now())
                    .issue(userId, "whoever", List.of("ADMIN")).value();

            me(forged).andExpect(problem(401, "INVALID_TOKEN"));
        }

        @Test
        void expiredTokenIsReportedAsExpired() throws Exception {
            long userId = registerUser(unique("old"));
            String expired = jwtService(securityProperties.jwt().secret(), Instant.now().minus(Duration.ofHours(1)))
                    .issue(userId, "old", List.of("USER")).value();

            me(expired)
                    .andExpect(problem(401, "TOKEN_EXPIRED"))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("error=\"invalid_token\"")));
        }

        @Test
        void tokenStopsWorkingAsSoonAsTheAccountIsDisabled() throws Exception {
            String username = unique("revoked");
            registerUser(username);
            String token = tokenFor(username, USER_PASSWORD);
            me(token).andExpect(status().isOk());

            jdbc.update("UPDATE app_user SET status = 'DISABLED' WHERE username = ?", username);

            me(token).andExpect(problem(401, "INVALID_TOKEN"));
        }

        @Test
        void publicEndpointsStillWorkWithABadToken() throws Exception {
            mockMvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                    .andExpect(status().isOk());
        }
    }

    // ================================================================== helpers

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    /** A token issuer that differs from the application's only in key and/or clock. */
    private JwtService jwtService(String secret, Instant now) {
        WmsSecurityProperties.Jwt jwt = securityProperties.jwt();
        WmsSecurityProperties properties = new WmsSecurityProperties(
                new WmsSecurityProperties.Jwt(secret, jwt.issuer(), jwt.audience(), jwt.accessTokenTtl(), jwt.clockSkew()),
                securityProperties.bcryptStrength(), securityProperties.lockout(), securityProperties.bootstrapAdmin());
        return new JwtService(properties, Clock.fixed(now, ZoneOffset.UTC));
    }
}
