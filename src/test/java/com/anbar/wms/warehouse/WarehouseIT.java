package com.anbar.wms.warehouse;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import com.anbar.wms.support.StockLedgerFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class WarehouseIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
    }

    @Test
    void createReturnsNormalizedWarehouse() throws Exception {
        String code = code();

        api.post("/api/v1/warehouses", """
                {"code": "%s", "name": " Central ", "description": "Main hub",
                 "address": {"addressLine": "1 Main St", "city": "Baku", "postalCode": "AZ1000", "countryCode": "az"}}"""
                .formatted(code.toLowerCase()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(code.toUpperCase()))
                .andExpect(jsonPath("$.name").value("Central"))
                .andExpect(jsonPath("$.address.countryCode").value("AZ"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void duplicateCodeIsConflict() throws Exception {
        String code = code();
        api.warehouse(code);

        api.post("/api/v1/warehouses", "{\"code\": \"%s\", \"name\": \"Again\"}".formatted(code.toLowerCase()))
                .andExpect(problem(409, "DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.field").value("code"));
    }

    @Test
    void invalidCodeIsRejected() throws Exception {
        api.post("/api/v1/warehouses", "{\"code\": \"has space\", \"name\": \"X\"}")
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("code"));
    }

    @Test
    void updateGetAndSearch() throws Exception {
        String code = code();
        long id = api.warehouse(code);

        api.put("/api/v1/warehouses/" + id, "{\"name\": \"Renamed\", \"description\": \"Cold storage\", \"version\": 0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));
        api.get("/api/v1/warehouses/" + id).andExpect(jsonPath("$.description").value("Cold storage"));
        api.get("/api/v1/warehouses?q=" + code.toLowerCase() + "&sort=code,asc")
                .andExpect(jsonPath("$.content[*].code", contains(code.toUpperCase())));
    }

    @Test
    void onlyAdminCanCreate() throws Exception {
        String username = unique("viewer");
        registerUser(username);

        mockMvc.perform(post("/api/v1/warehouses").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(username, USER_PASSWORD)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\": \"%s\", \"name\": \"X\"}".formatted(code())))
                .andExpect(problem(403, "ACCESS_DENIED"));
    }

    @Test
    void managersSeeOnlyTheirAssignedWarehouses() throws Exception {
        String prefix = code().substring(0, 8);
        long assigned = api.warehouse(prefix + "-A");
        long other = api.warehouse(prefix + "-B");
        String username = unique("manager");
        long managerId = registerUser(username);
        api.makeWarehouseManager(managerId);
        api.assignManager(assigned, managerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(username));
        String token = tokenFor(username, USER_PASSWORD);

        mockMvc.perform(get("/api/v1/warehouses?q=" + prefix).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(jsonPath("$.content[*].id", contains((int) assigned)));
        mockMvc.perform(get("/api/v1/warehouses/" + other).header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        api.get("/api/v1/warehouses/" + assigned + "/managers")
                .andExpect(jsonPath("$.content[*].username", hasItem(username)));
    }

    @Test
    void managerAssignmentRequiresTheRoleAndAnActiveWarehouse() throws Exception {
        long warehouse = api.warehouse(code());
        long plainUser = registerUser(unique("plain"));

        api.assignManager(warehouse, plainUser).andExpect(problem(422, "NOT_A_WAREHOUSE_MANAGER"));

        api.makeWarehouseManager(plainUser);
        api.setWarehouseActive(warehouse, false);
        api.assignManager(warehouse, plainUser).andExpect(problem(422, "INACTIVE_RESOURCE"));

        api.setWarehouseActive(warehouse, true);
        api.assignManager(warehouse, plainUser).andExpect(status().isOk());
    }

    @Test
    void warehouseHoldingStockCannotBeDeactivated() throws Exception {
        long warehouse = api.warehouse(code());
        long category = api.category(unique("Cat"));
        long pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        long product = api.product(unique("w"), "Stocked", category, pieces, "0");
        new StockLedgerFixture(jdbc).receive(warehouse, product, null, "4");

        api.patch("/api/v1/warehouses/" + warehouse + "/status", "{\"active\": false}")
                .andExpect(problem(409, "STOCK_ON_HAND"));
    }

    private static String code() {
        return unique("WH").replace("-", "").substring(0, 12);
    }
}
