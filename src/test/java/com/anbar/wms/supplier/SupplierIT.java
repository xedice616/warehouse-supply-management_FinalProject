package com.anbar.wms.supplier;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class SupplierIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
    }

    @Test
    void createsSupplierWithGeneratedCode() throws Exception {
        String taxId = taxId();

        api.post("/api/v1/suppliers", """
                {"companyName": "Caspian Fasteners", "taxIdentificationNumber": "%s", "contactPerson": "Rauf",
                 "email": "Sales@Caspian.AZ", "phone": "+994 12 555-01-23", "paymentTermsDays": 30,
                 "address": {"city": "Baku", "countryCode": "az"}, "description": "Bolts and nuts"}""".formatted(taxId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(matchesPattern("SUP-\\d{6}")))
                .andExpect(jsonPath("$.companyName").value("Caspian Fasteners"))
                .andExpect(jsonPath("$.taxIdentificationNumber").value(taxId))
                .andExpect(jsonPath("$.email").value("sales@caspian.az"))
                .andExpect(jsonPath("$.address.countryCode").value("AZ"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void duplicateTaxIdIsConflict() throws Exception {
        String taxId = taxId();
        api.supplier("First", taxId);

        api.post("/api/v1/suppliers", "{\"companyName\": \"Second\", \"taxIdentificationNumber\": \"%s\"}".formatted(taxId))
                .andExpect(problem(409, "DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.field").value("taxIdentificationNumber"));
    }

    @Test
    void invalidContactDataIsRejected() throws Exception {
        api.post("/api/v1/suppliers", "{\"companyName\": \"X\", \"email\": \"not-an-email\"}")
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("email"));
        api.post("/api/v1/suppliers", "{\"companyName\": \"X\", \"phone\": \"12ab\"}")
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("phone"));
        api.post("/api/v1/suppliers", "{\"companyName\": \" \"}")
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("companyName"));
        api.post("/api/v1/suppliers", """
                {"companyName": "X", "taxIdentificationNumber": "12345", "address": {"countryCode": "AZ"}}""")
                .andExpect(problem(400, "VALIDATION_FAILED"));
    }

    @Test
    void updateUsesOptimisticVersioning() throws Exception {
        long id = api.supplier("Before", taxId());
        String update = "{\"companyName\": \"After\", \"phone\": \"+994501234567\", \"version\": 0}";

        api.put("/api/v1/suppliers/" + id, update)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.companyName").value("After"))
                .andExpect(jsonPath("$.version").value(1));
        api.put("/api/v1/suppliers/" + id, update).andExpect(problem(409, "CONCURRENT_MODIFICATION"));
    }

    @Test
    void deactivatedSupplierCannotDeliver() throws Exception {
        long supplier = api.supplier("Gone", taxId());
        long pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        long product = api.product(unique("sp"), "Supplied", api.category(unique("Sup")), pieces, "0");
        long warehouse = api.warehouse(unique("SW").replace("-", ""));
        String delivery = "{\"productId\": %d, \"warehouseId\": %d, \"quantity\": 5, \"supplierId\": %d}"
                .formatted(product, warehouse, supplier);

        api.post("/api/v1/stock-movements/inbound", delivery).andExpect(status().isCreated())
                .andExpect(jsonPath("$.supplier.id").value(supplier));
        api.get("/api/v1/suppliers/" + supplier + "/stock-movements")
                .andExpect(jsonPath("$.totalElements").value(1));
        api.delete("/api/v1/suppliers/" + supplier).andExpect(problem(409, "RESOURCE_IN_USE"));

        api.patch("/api/v1/suppliers/" + supplier + "/status", "{\"active\": false}")
                .andExpect(jsonPath("$.active").value(false));
        api.post("/api/v1/stock-movements/inbound", delivery).andExpect(problem(422, "INACTIVE_RESOURCE"));
    }

    @Test
    void unusedSupplierCanBeDeleted() throws Exception {
        long id = api.supplier("Temporary", taxId());

        api.delete("/api/v1/suppliers/" + id).andExpect(status().isNoContent());
        api.get("/api/v1/suppliers/" + id).andExpect(problem(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void searchPagesAndSorts() throws Exception {
        String prefix = unique("Acme");
        for (String suffix : new String[] {"Beta", "Alpha", "Gamma"}) {
            api.supplier(prefix + " " + suffix, taxId());
        }

        api.get("/api/v1/suppliers?q=%s&size=2&sort=companyName,desc".formatted(prefix.toLowerCase()))
                .andExpect(jsonPath("$.content[*].companyName", contains(prefix + " Gamma", prefix + " Beta")))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        api.get("/api/v1/suppliers?q=%s&size=2&page=1&sort=companyName,desc".formatted(prefix.toLowerCase()))
                .andExpect(jsonPath("$.content[*].companyName", contains(prefix + " Alpha")));
        api.get("/api/v1/suppliers?sort=taxId").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    @Test
    void managersReadUsersCannot() throws Exception {
        String managerName = unique("smgr");
        long manager = registerUser(managerName);
        api.makeWarehouseManager(manager);
        String managerToken = bearer(tokenFor(managerName, USER_PASSWORD));
        String userName = unique("suser");
        registerUser(userName);

        mockMvc.perform(get("/api/v1/suppliers").header(HttpHeaders.AUTHORIZATION, managerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/suppliers").header(HttpHeaders.AUTHORIZATION, managerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"companyName\": \"Nope\"}"))
                .andExpect(problem(403, "ACCESS_DENIED"));
        mockMvc.perform(get("/api/v1/suppliers").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(userName, USER_PASSWORD))))
                .andExpect(problem(403, "ACCESS_DENIED"));
    }

    private static String taxId() {
        return "%010d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L));
    }
}
