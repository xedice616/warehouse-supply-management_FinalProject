package com.anbar.wms.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Integration tests against the local PostgreSQL database from {@code .env} (or {@code WMS_TEST_DB_URL}), with the
 * production role split: Flyway migrates as {@code wms_owner}, the application connects as {@code wms_app}.
 *
 * <p>The database is shared and persistent, so tests that commit data use {@link #unique(String)} names and
 * assert only on their own data.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class AbstractIntegrationTest {

    protected static final String APP_ROLE = "wms_app";
    protected static final String OWNER_ROLE = "wms_owner";

    protected static final String ADMIN_USERNAME = "it-admin";
    protected static final String ADMIN_PASSWORD = "It1-" + UUID.randomUUID();
    protected static final String USER_PASSWORD = "Correct-horse-42";

    private static boolean testAdminReady;

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /**
     * The database may already have a real administrator, so the bootstrap won't create one for the tests.
     * The test admin gets a fresh random password every run, so no usable admin credential lives in the code.
     */
    @BeforeEach
    void ensureTestAdmin() {
        if (testAdminReady) {
            return;
        }
        String hash = passwordEncoder.encode(ADMIN_PASSWORD);
        int updated = jdbcTemplate.update("""
                UPDATE app_user SET password_hash = ?, status = 'ACTIVE', failed_login_attempts = 0, locked_until = NULL
                WHERE username = ?""", hash, ADMIN_USERNAME);
        if (updated == 0) {
            jdbcTemplate.update("""
                    INSERT INTO app_user (id, username, email, password_hash, full_name, status,
                        version, created_at, created_by, updated_at, updated_by)
                    VALUES (nextval('app_user_seq'), ?, 'it-admin@example.test', ?, 'Integration test admin', 'ACTIVE',
                        0, now(), 'integration-tests', now(), 'integration-tests')""", ADMIN_USERNAME, hash);
        }
        jdbcTemplate.update("""
                INSERT INTO user_role (user_id, role_id)
                SELECT u.id, r.id FROM app_user u, role r WHERE u.username = ? AND r.code = 'ADMIN'
                ON CONFLICT DO NOTHING""", ADMIN_USERNAME);
        testAdminReady = true;
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    protected ResultActions postJson(String path, String json) throws Exception {
        return mockMvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    protected ResultActions register(String username, String email, String password) throws Exception {
        return postJson("/api/v1/auth/register", """
                {"username": "%s", "email": "%s", "password": "%s", "fullName": "Test Person"}
                """.formatted(username, email, password));
    }

    protected ResultActions login(String usernameOrEmail, String password) throws Exception {
        return postJson("/api/v1/auth/login", """
                {"usernameOrEmail": "%s", "password": "%s"}
                """.formatted(usernameOrEmail, password));
    }

    protected String tokenFor(String usernameOrEmail, String password) throws Exception {
        String body = login(usernameOrEmail, password).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.accessToken");
    }

    protected String adminToken() throws Exception {
        return tokenFor(ADMIN_USERNAME, ADMIN_PASSWORD);
    }

    protected long registerUser(String username) throws Exception {
        String body = register(username, username + "@example.test", USER_PASSWORD)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    protected static String bearer(String token) {
        return "Bearer " + token;
    }

    protected static long idOf(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }
}
