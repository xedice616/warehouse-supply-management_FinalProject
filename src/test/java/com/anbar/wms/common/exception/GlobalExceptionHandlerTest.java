package com.anbar.wms.common.exception;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.common.web.CorrelationIdFilter;
import com.anbar.wms.security.AuthenticatedUserResolver;
import com.anbar.wms.security.config.SecurityConfig;
import com.anbar.wms.security.jwt.JwtService;
import com.anbar.wms.security.web.ProblemAccessDeniedHandler;
import com.anbar.wms.security.web.ProblemAuthenticationEntryPoint;
import com.anbar.wms.security.web.ProblemResponseWriter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Web slice with the real security configuration: requests run as an authenticated user ({@link WithMockUser})
 * so the probe endpoints reach the controller; the JWT collaborators are mocks because no token is involved.
 */
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ProbeController.class)
@Import({GlobalExceptionHandlerTest.ProbeController.class, GlobalExceptionHandlerTest.FixedClock.class,
        ProblemDetailFactory.class, SecurityConfig.class, ProblemAuthenticationEntryPoint.class,
        ProblemAccessDeniedHandler.class, ProblemResponseWriter.class})
@WithMockUser
class GlobalExceptionHandlerTest {

    static final Instant NOW = Instant.parse("2026-11-07T10:15:30Z");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private AuthenticatedUserResolver authenticatedUserResolver;

    // ------------------------------------------------------------- security

    @Test
    @WithAnonymousUser
    void anonymousCallerGets401ProblemFromTheSecurityChain() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(problem(401, "AUTHENTICATION_REQUIRED"))
                .andExpect(header().string("WWW-Authenticate", "Bearer realm=\"wms\""));
    }

    @Test
    @WithMockUser(roles = "USER")
    void methodSecurityDenialIs403Problem() throws Exception {
        mockMvc.perform(get("/probe/admin-only"))
                .andExpect(problem(403, "ACCESS_DENIED"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void methodSecurityAllowsTheRightRole() throws Exception {
        mockMvc.perform(get("/probe/admin-only")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------- request body problems

    @Test
    void beanValidationFailuresListEveryFieldSorted() throws Exception {
        postJson("""
                {"name": " ", "amount": -1, "lines": [{"qty": 0}]}
                """)
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[1].field").value("lines[0].qty"))
                .andExpect(jsonPath("$.errors[2].field").value("name"))
                .andExpect(jsonPath("$.errors[0].rejectedValue").doesNotExist());
    }

    @Test
    void unknownPropertyIsRejectedInsteadOfIgnored() throws Exception {
        postJson("""
                {"name": "Widget", "amount": 1, "quantity": 500}
                """)
                .andExpect(problem(400, "MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Unknown property 'quantity'"));
    }

    @Test
    void unknownNestedPropertyReportsItsPath() throws Exception {
        postJson("""
                {"name": "Widget", "amount": 1, "lines": [{"qty": 1}, {"qty": 2, "stock": 9}]}
                """)
                .andExpect(problem(400, "MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Unknown property 'lines[1].stock'"));
    }

    @Test
    void wrongValueTypeNamesTheField() throws Exception {
        postJson("""
                {"name": "Widget", "amount": "lots"}
                """)
                .andExpect(problem(400, "MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Invalid value for 'amount'"));
    }

    @Test
    void syntacticallyBrokenJsonIsMalformed() throws Exception {
        postJson("{\"name\": ")
                .andExpect(problem(400, "MALFORMED_REQUEST"));
    }

    // ------------------------------------------------------------- parameters and routing

    @Test
    void constraintOnRequestParameterIsValidationFailure() throws Exception {
        mockMvc.perform(get("/probe/param").param("size", "0"))
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    void unconvertibleParameterIsInvalidParameter() throws Exception {
        mockMvc.perform(get("/probe/param").param("size", "abc"))
                .andExpect(problem(400, "INVALID_PARAMETER"));
    }

    @Test
    void unsupportedMethodAndMediaTypeHaveCodes() throws Exception {
        mockMvc.perform(delete("/probe/body"))
                .andExpect(problem(405, "METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/probe/body").contentType(MediaType.TEXT_PLAIN).content("hi"))
                .andExpect(problem(415, "UNSUPPORTED_MEDIA_TYPE"));
    }

    // ------------------------------------------------------------- application exceptions

    @Test
    void notFoundCarriesResourceAndId() throws Exception {
        mockMvc.perform(get("/probe/not-found"))
                .andExpect(problem(404, "RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:wms:problem:resource-not-found"))
                .andExpect(jsonPath("$.detail").value("Product with id '42' was not found"))
                .andExpect(jsonPath("$.resource").value("Product"))
                .andExpect(jsonPath("$.id").value("42"));
    }

    @Test
    void businessExceptionPropertiesBecomeProblemMembers() throws Exception {
        mockMvc.perform(get("/probe/business"))
                .andExpect(problem(422, "BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.detail").value("Only 3 left"))
                .andExpect(jsonPath("$.available").value(3));
    }

    @Test
    void optimisticLockFailureIsConflict() throws Exception {
        mockMvc.perform(get("/probe/optimistic"))
                .andExpect(problem(409, "CONCURRENT_MODIFICATION"));
    }

    @Test
    void dataIntegrityViolationDoesNotLeakConstraintNames() throws Exception {
        mockMvc.perform(get("/probe/integrity"))
                .andExpect(problem(409, "DATA_INTEGRITY_VIOLATION"))
                .andExpect(content().string(not(containsString("uq_product_sku"))));
    }

    @Test
    void unexpectedExceptionIsGeneric500() throws Exception {
        mockMvc.perform(get("/probe/boom"))
                .andExpect(problem(500, "INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))));
    }

    // ------------------------------------------------------------- shared members

    @Test
    void everyProblemCarriesTimestampAndCorrelationId() throws Exception {
        mockMvc.perform(get("/probe/not-found").header(CorrelationIdFilter.HEADER, "client-trace-0001"))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "client-trace-0001"))
                .andExpect(jsonPath("$.correlationId").value("client-trace-0001"))
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()));
    }

    // ------------------------------------------------------------- helpers

    private ResultActions postJson(String json) throws Exception {
        return mockMvc.perform(post("/probe/body").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static org.springframework.test.web.servlet.ResultMatcher problem(int status, String code) {
        return result -> {
            status().is(status).match(result);
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON).match(result);
            jsonPath("$.status").value(status).match(result);
            jsonPath("$.code").value(code).match(result);
            jsonPath("$.timestamp").exists().match(result);
            jsonPath("$.correlationId").exists().match(result);
        };
    }

    // ------------------------------------------------------------- fixtures

    record ProbeRequest(@NotBlank String name, @NotNull @Positive Integer amount, List<@Valid ProbeLine> lines) {
    }

    record ProbeLine(@NotNull @Positive Integer qty) {
    }

    @RestController
    @RequestMapping("/probe")
    static class ProbeController {

        @PostMapping("/body")
        void body(@Valid @RequestBody ProbeRequest request) {
        }

        @GetMapping("/param")
        void param(@RequestParam @Min(1) int size) {
        }

        @GetMapping("/not-found")
        void notFound() {
            throw new ResourceNotFoundException("Product", 42);
        }

        @GetMapping("/business")
        void business() {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Only 3 left", Map.of("available", 3));
        }

        @GetMapping("/optimistic")
        void optimistic() {
            throw new OptimisticLockingFailureException("Row was updated by another transaction");
        }

        @GetMapping("/integrity")
        void integrity() {
            throw new DataIntegrityViolationException(
                    "duplicate key value violates unique constraint \"uq_product_sku\"");
        }

        @GetMapping("/boom")
        void boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/admin-only")
        @PreAuthorize("hasRole('ADMIN')")
        void adminOnly() {
        }
    }

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }
}
