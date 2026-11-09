package com.anbar.wms.identity;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class UserManagementIT extends AbstractIntegrationTest {

    private static final String NEW_PASSWORD = "New-passw0rd-99";

    private ApiFixtures admin;
    private String username;
    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        admin = new ApiFixtures(mockMvc, adminToken());
        username = unique("um-user");
        userId = registerUser(username);
    }

    @Test
    void adminCanFetchAUserAndMissingUsersAre404() throws Exception {
        admin.get("/api/v1/users/" + userId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        admin.get("/api/v1/users/999999999").andExpect(problem(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void disablingAUserBlocksLoginAndRejectsExistingTokens() throws Exception {
        String token = tokenFor(username, USER_PASSWORD);
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk());

        admin.patch("/api/v1/users/" + userId + "/status", "{\"active\": false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        // Existing token is rejected on the next request (the account it names is no longer valid), and a fresh
        // login is refused.
        mockMvc.perform(get("/api/v1/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(problem(401, "INVALID_TOKEN"));
        login(username, USER_PASSWORD).andExpect(problem(401, "ACCOUNT_DISABLED"));

        admin.patch("/api/v1/users/" + userId + "/status", "{\"active\": true}").andExpect(status().isOk());
        login(username, USER_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void adminCanUnlockAnAccountLockedByFailedLogins() throws Exception {
        for (int i = 0; i < 5; i++) {
            login(username, "wrong-password");
        }
        login(username, USER_PASSWORD).andExpect(problem(401, "ACCOUNT_LOCKED"));

        admin.post("/api/v1/users/" + userId + "/unlock", null).andExpect(status().isOk());
        login(username, USER_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void adminCanResetAPasswordAndTheOldOneStopsWorking() throws Exception {
        admin.put("/api/v1/users/" + userId + "/password", "{\"newPassword\": \"%s\"}".formatted(NEW_PASSWORD))
                .andExpect(status().isOk());

        login(username, USER_PASSWORD).andExpect(problem(401, "INVALID_CREDENTIALS"));
        login(username, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void usersCanChangeTheirOwnPassword() throws Exception {
        String token = tokenFor(username, USER_PASSWORD);

        changePassword(token, "wrong-current", NEW_PASSWORD).andExpect(problem(401, "INVALID_CREDENTIALS"));
        changePassword(token, USER_PASSWORD, "weak").andExpect(problem(400, "VALIDATION_FAILED"));
        changePassword(token, USER_PASSWORD, NEW_PASSWORD).andExpect(status().isNoContent());

        login(username, USER_PASSWORD).andExpect(problem(401, "INVALID_CREDENTIALS"));
        login(username, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    void ordinaryUsersCannotAdministerOtherUsers() throws Exception {
        String token = tokenFor(username, USER_PASSWORD);
        mockMvc.perform(patch("/api/v1/users/" + userId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"active\": false}"))
                .andExpect(problem(403, "ACCESS_DENIED"));
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(String token, String current,
                                                                              String next) throws Exception {
        return mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/v1/auth/change-password")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(current, next)));
    }
}
