package com.anbar.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.common.web.CorrelationIdFilter;
import com.anbar.wms.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class ApplicationIT extends AbstractIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayAppliesBaselineIntoWmsSchema() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(applied).allSatisfy(m -> assertThat(m.getState().isApplied()).isTrue());
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("wms");
    }

    @Test
    void applicationConnectsAsLeastPrivilegedRole() {
        assertThat(jdbc.queryForObject("SELECT current_user", String.class)).isEqualTo(APP_ROLE);
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo("wms");
    }

    @Test
    void applicationRoleCannotChangeSchema() {
        assertThatThrownBy(() -> jdbc.execute("CREATE TABLE wms.ddl_probe (id INT)"))
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex)).hasMessageContaining("permission denied"));
    }

    @Test
    void applicationRoleGetsDmlOnFutureTablesAndSequences() {
        Integer defaultAcls = jdbc.queryForObject("""
                SELECT count(*) FROM pg_default_acl d
                JOIN pg_namespace n ON n.oid = d.defaclnamespace
                WHERE n.nspname = 'wms' AND d.defaclobjtype IN ('r', 'S')
                  AND array_to_string(d.defaclacl, ',') LIKE '%' || ? || '=%'
                """, Integer.class, APP_ROLE);

        assertThat(defaultAcls).isEqualTo(2);
    }

    @Test
    void appendOnlyTriggerFunctionIsInstalled() {
        Integer functions = jdbc.queryForObject("""
                SELECT count(*) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                WHERE n.nspname = 'wms' AND p.proname = 'forbid_modification'
                """, Integer.class);

        assertThat(functions).isEqualTo(1);
    }

    @Test
    void healthAndProbesAreUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void openApiDocumentDeclaresBearerAuth() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("Warehouse & Supply Management API"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    void anonymousRequestToPrivateEndpointGetsCorrelated401() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").header(CorrelationIdFilter.HEADER, "it-correlation-001"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(CorrelationIdFilter.HEADER, "it-correlation-001"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.correlationId").value("it-correlation-001"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void authenticatedRequestToUnknownEndpointGets404Problem() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist").header("Authorization", bearer(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value("urn:wms:problem:endpoint-not-found"));
    }
}
