package com.anbar.wms.inventory;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import com.anbar.wms.support.StockLedgerFixture;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stock in this test: bolts (min 50) 30 in north + 15 in south; nuts (batch-tracked) lots L1 = 5 and L2 = 7 in
 * north; washers (min 10) nowhere.
 */
class StockQueryIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;
    private long north;
    private long south;
    private long bolts;
    private long nuts;
    private long washers;
    private String boltsSku;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        StockLedgerFixture ledger = new StockLedgerFixture(jdbc);
        long pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        long category = api.category(unique("Fasteners"));
        north = api.warehouse(code("N"));
        south = api.warehouse(code("S"));
        boltsSku = unique("bolt");
        bolts = api.product(boltsSku, "Bolt", category, pieces, "50");
        nuts = api.batchProduct(unique("nut"), category, pieces);
        washers = api.product(unique("washer"), "Washer", category, pieces, "10");

        ledger.receive(north, bolts, null, "30");
        ledger.receive(south, bolts, null, "10");
        ledger.receive(south, bolts, null, "5");
        long lateLot = ledger.batch(nuts, "L2", LocalDate.of(2027, 6, 1));
        long earlyLot = ledger.batch(nuts, "L1", LocalDate.of(2027, 1, 1));
        ledger.receive(north, nuts, lateLot, "7");
        ledger.receive(north, nuts, earlyLot, "5");
    }

    @Test
    void productsInAWarehouseAreTotalledPerProduct() throws Exception {
        api.get("/api/v1/warehouses/" + north + "/products?sort=name")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].productId").value(nuts))
                .andExpect(jsonPath("$.content[0].quantity").value(12))
                .andExpect(jsonPath("$.content[0].belowMinimum").value(false))
                .andExpect(jsonPath("$.content[1].productId").value(bolts))
                .andExpect(jsonPath("$.content[1].quantity").value(30))
                .andExpect(jsonPath("$.content[1].belowMinimum").value(true));
    }

    @Test
    void stockLinesShowEachBatch() throws Exception {
        api.get("/api/v1/warehouses/" + north + "/stock?sort=quantity,desc")
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content[*].quantity", contains(30.0, 7.0, 5.0)));
    }

    @Test
    void productStockInAWarehouseListsBatchesByExpiry() throws Exception {
        api.get("/api/v1/warehouses/" + north + "/stock/" + nuts)
                .andExpect(jsonPath("$.quantity").value(12))
                .andExpect(jsonPath("$.batches[*].batchNumber", contains("L1", "L2")))
                .andExpect(jsonPath("$.batches[*].quantity", contains(5.0, 7.0)));
        api.get("/api/v1/warehouses/" + south + "/stock/" + nuts)
                .andExpect(jsonPath("$.quantity").value(0))
                .andExpect(jsonPath("$.batches").isEmpty());
    }

    @Test
    void productStockAcrossWarehousesMatchesTheLedger() throws Exception {
        String body = api.get("/api/v1/products/" + bolts + "/stock")
                .andExpect(jsonPath("$.totalQuantity").value(45))
                .andExpect(jsonPath("$.minStockQuantity").value(50))
                .andExpect(jsonPath("$.belowMinimum").value(true))
                .andExpect(jsonPath("$.warehouses.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        BigDecimal ledgerTotal = jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN destination_warehouse_id IS NOT NULL THEN quantity ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN source_warehouse_id IS NOT NULL THEN quantity ELSE 0 END), 0)
                FROM stock_movement WHERE product_id = ?""", BigDecimal.class, bolts);
        assertThat(new BigDecimal(JsonPath.read(body, "$.totalQuantity").toString())).isEqualByComparingTo(ledgerTotal);
    }

    @Test
    void lowStockPerWarehouseAndOverall() throws Exception {
        Map<String, Object> inSouth = findLowStock("?warehouseId=" + south, bolts);
        assertAmounts(inSouth, "15", "35");

        Map<String, Object> overall = findLowStock("", bolts);
        assertAmounts(overall, "45", "5");

        Map<String, Object> neverStocked = findLowStock("", washers);
        assertAmounts(neverStocked, "0", "10");

        assertThat(findLowStock("?warehouseId=" + north, nuts)).isNull();
    }

    @Test
    void managersSeeStockOnlyForTheirWarehouses() throws Exception {
        String username = unique("mgr");
        long manager = registerUser(username);
        api.makeWarehouseManager(manager);
        api.assignManager(north, manager).andExpect(status().isOk());
        String token = bearer(tokenFor(username, USER_PASSWORD));

        mockMvc.perform(get("/api/v1/products/" + bolts + "/stock").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.totalQuantity").value(30))
                .andExpect(jsonPath("$.warehouses.length()").value(1));
        mockMvc.perform(get("/api/v1/warehouses/" + south + "/stock").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/products/low-stock?warehouseId=" + south).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void userWithoutAssignedWarehousesSeesNoStock() throws Exception {
        String username = unique("nobody");
        registerUser(username);
        String token = bearer(tokenFor(username, USER_PASSWORD));

        mockMvc.perform(get("/api/v1/products/" + bolts + "/stock").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.totalQuantity").value(0))
                .andExpect(jsonPath("$.warehouses").isEmpty());
        mockMvc.perform(get("/api/v1/products/low-stock").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void stockCannotBeModifiedThroughTheApi() throws Exception {
        String admin = bearer(adminToken());
        String path = "/api/v1/warehouses/" + north + "/stock";

        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(problem(405, "METHOD_NOT_ALLOWED"));
        mockMvc.perform(put(path + "/" + bolts).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(problem(405, "METHOD_NOT_ALLOWED"));
        mockMvc.perform(patch(path + "/" + bolts).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(problem(405, "METHOD_NOT_ALLOWED"));
        mockMvc.perform(delete(path + "/" + bolts).header(HttpHeaders.AUTHORIZATION, admin)).andExpect(problem(405, "METHOD_NOT_ALLOWED"));
        mockMvc.perform(post("/api/v1/products/" + bolts + "/stock").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(problem(405, "METHOD_NOT_ALLOWED"));
    }

    @Test
    void unknownSortIsRejected() throws Exception {
        api.get("/api/v1/products/low-stock?sort=password").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
        api.get("/api/v1/warehouses/" + north + "/stock?sort=batch_id").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    /** Pages through the low-stock list (other test runs' products are in it too) looking for one product. */
    private Map<String, Object> findLowStock(String query, long productId) throws Exception {
        String separator = query.isEmpty() ? "?" : "&";
        for (int page = 0; page < 100; page++) {
            String body = api.get("/api/v1/products/low-stock" + query + separator + "size=100&sort=sku&page=" + page)
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            List<Map<String, Object>> matches = JsonPath.read(body, "$.content[?(@.productId == " + productId + ")]");
            if (!matches.isEmpty()) {
                return matches.get(0);
            }
            if ((Boolean) JsonPath.read(body, "$.last")) {
                return null;
            }
        }
        return null;
    }

    private static void assertAmounts(Map<String, Object> row, String quantity, String shortage) {
        assertThat(row).isNotNull();
        assertThat(new BigDecimal(row.get("quantity").toString())).isEqualByComparingTo(quantity);
        assertThat(new BigDecimal(row.get("shortage").toString())).isEqualByComparingTo(shortage);
    }

    private static String code(String prefix) {
        return unique(prefix).replace("-", "");
    }
}
