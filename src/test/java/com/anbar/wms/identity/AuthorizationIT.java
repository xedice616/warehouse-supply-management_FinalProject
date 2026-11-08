package com.anbar.wms.identity;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** Role-based access to the ADMIN-only user administration endpoints. */
class AuthorizationIT extends AbstractIntegrationTest {

    @Test
    void anonymousCallerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/users")).andExpect(problem(401, "AUTHENTICATION_REQUIRED"));
    }

    @Test
    void userRoleIsForbidden() throws Exception {
        String username = unique("plain");
        registerUser(username);

        listUsers(tokenFor(username, USER_PASSWORD)).andExpect(problem(403, "ACCESS_DENIED"));
    }

    @Test
    void warehouseManagerIsForbiddenToo() throws Exception {
        String username = unique("manager");
        long id = registerUser(username);
        replaceRoles(adminToken(), id, "[\"WAREHOUSE_MANAGER\"]").andExpect(status().isOk());

        listUsers(tokenFor(username, USER_PASSWORD)).andExpect(problem(403, "ACCESS_DENIED"));
    }

    @Test
    void adminCanListUsers() throws Exception {
        listUsers(adminToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.totalElements").isNumber())
                .andExpect(jsonPath("$.content[*].passwordHash").isEmpty());
    }

    @Test
    void roleChangeAppliesToExistingTokensImmediately() throws Exception {
        String username = unique("promoted");
        long id = registerUser(username);
        String userToken = tokenFor(username, USER_PASSWORD);

        replaceRoles(adminToken(), id, "[\"WAREHOUSE_MANAGER\", \"USER\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles", contains("USER", "WAREHOUSE_MANAGER")));

        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(jsonPath("$.roles", contains("USER", "WAREHOUSE_MANAGER")));
    }

    @Test
    void userCannotChangeRolesEvenTheirOwn() throws Exception {
        String username = unique("climber");
        long id = registerUser(username);

        replaceRoles(tokenFor(username, USER_PASSWORD), id, "[\"ADMIN\"]").andExpect(problem(403, "ACCESS_DENIED"));
    }

    @Test
    void invalidRoleNameIsRejected() throws Exception {
        long id = registerUser(unique("target"));

        replaceRoles(adminToken(), id, "[\"SUPERUSER\"]").andExpect(problem(400, "MALFORMED_REQUEST"));
        replaceRoles(adminToken(), id, "[]").andExpect(problem(400, "VALIDATION_FAILED"));
    }

    @Test
    void sortingByUnlistedPropertyIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users").param("sort", "passwordHash")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    private ResultActions listUsers(String token) throws Exception {
        return mockMvc.perform(get("/api/v1/users").header(HttpHeaders.AUTHORIZATION, bearer(token)));
    }

    private ResultActions replaceRoles(String token, long userId, String rolesJson) throws Exception {
        return mockMvc.perform(put("/api/v1/users/{id}/roles", userId)
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"roles\": " + rolesJson + "}"));
    }
}
