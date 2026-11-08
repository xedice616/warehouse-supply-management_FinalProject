package com.anbar.wms.inventory;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class StockMovementIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;
    private long pieces;
    private long category;
    private long product;
    private long first;
    private long second;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        category = api.category(unique("Moves"));
        product = api.product(unique("mv"), "Moved product", category, pieces, "0");
        first = api.warehouse(code());
        second = api.warehouse(code());
    }

    @Nested
    class Inbound {

        @Test
        void receivesStockAndRecordsTheMovement() throws Exception {
            api.post("/api/v1/stock-movements/inbound", """
                    {"productId": %d, "warehouseId": %d, "quantity": 25, "referenceNumber": "INV-77", "reason": "Delivery"}"""
                    .formatted(product, first))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movementNumber").value(matchesPattern("SM-\\d{4}-\\d{6}")))
                    .andExpect(jsonPath("$.movementType").value("INBOUND"))
                    .andExpect(jsonPath("$.quantity").value(25))
                    .andExpect(jsonPath("$.destinationWarehouse.id").value(first))
                    .andExpect(jsonPath("$.sourceWarehouse").doesNotExist())
                    .andExpect(jsonPath("$.destinationBalanceAfter").value(25))
                    .andExpect(jsonPath("$.unitCost").value(1.5))
                    .andExpect(jsonPath("$.referenceNumber").value("INV-77"))
                    .andExpect(jsonPath("$.performedBy.username").value(ADMIN_USERNAME))
                    .andExpect(jsonPath("$.movementDate").isString());

            assertThat(balance(first)).isEqualByComparingTo("25");
        }

        @Test
        void quantityMustBePositiveAndFitTheUnit() throws Exception {
            api.inbound(product, first, "0").andExpect(problem(400, "VALIDATION_FAILED"));
            api.inbound(product, first, "-5").andExpect(problem(400, "VALIDATION_FAILED"));
            api.inbound(product, first, "2.5").andExpect(problem(400, "INVALID_QUANTITY_SCALE"));
            assertThat(balance(first)).isZero();
        }

        @Test
        void inactiveProductIsRejected() throws Exception {
            api.patch("/api/v1/products/" + product + "/status", "{\"active\": false}").andExpect(status().isOk());

            api.inbound(product, first, "1").andExpect(problem(422, "INACTIVE_RESOURCE"));
        }

        @Test
        void inactiveWarehouseIsRejected() throws Exception {
            api.setWarehouseActive(first, false);

            api.inbound(product, first, "1").andExpect(problem(422, "INACTIVE_RESOURCE"));
        }

        @Test
        void batchRulesAreEnforced() throws Exception {
            long tracked = api.batchProduct(unique("lot"), category, pieces);

            api.inbound(tracked, first, "1").andExpect(problem(422, "BATCH_REQUIRED"));
            api.post("/api/v1/stock-movements/inbound", """
                    {"productId": %d, "warehouseId": %d, "quantity": 1, "batchNumber": "L1"}""".formatted(tracked, first))
                    .andExpect(problem(422, "EXPIRY_DATE_REQUIRED"));
            api.post("/api/v1/stock-movements/inbound", """
                    {"productId": %d, "warehouseId": %d, "quantity": 1, "batchNumber": "OLD", "expiresOn": "2020-01-01"}"""
                    .formatted(tracked, first))
                    .andExpect(problem(422, "BATCH_EXPIRED"));
            api.post("/api/v1/stock-movements/inbound", """
                    {"productId": %d, "warehouseId": %d, "quantity": 3, "batchNumber": "L1", "expiresOn": "2099-01-01"}"""
                    .formatted(tracked, first))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.batch.batchNumber").value("L1"));
            api.post("/api/v1/stock-movements/inbound", """
                    {"productId": %d, "warehouseId": %d, "quantity": 1, "batchNumber": "L1"}""".formatted(product, first))
                    .andExpect(problem(422, "BATCH_NOT_ALLOWED"));
        }
    }

    @Nested
    class Outbound {

        @Test
        void issuesStock() throws Exception {
            api.inbound(product, first, "10").andExpect(status().isCreated());

            api.outbound(product, first, "4")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movementType").value("OUTBOUND"))
                    .andExpect(jsonPath("$.sourceBalanceAfter").value(6));
            assertThat(balance(first)).isEqualByComparingTo("6");
        }

        @Test
        void cannotTakeMoreThanIsOnHand() throws Exception {
            api.inbound(product, first, "10").andExpect(status().isCreated());

            api.outbound(product, first, "11")
                    .andExpect(problem(422, "INSUFFICIENT_STOCK"))
                    .andExpect(jsonPath("$.available").value(10))
                    .andExpect(jsonPath("$.requested").value(11));
            api.outbound(product, second, "1").andExpect(problem(422, "INSUFFICIENT_STOCK"));

            assertThat(balance(first)).isEqualByComparingTo("10");
            assertThat(movementCount()).isEqualTo(1);
        }

        @Test
        void negativeQuantitiesNeverReachTheLedger() throws Exception {
            api.outbound(product, first, "-3").andExpect(problem(400, "VALIDATION_FAILED"));
            assertThat(movementCount()).isZero();
        }

        @Test
        void expiredBatchCanOnlyBeWrittenOff() throws Exception {
            long tracked = api.batchProduct(unique("exp"), category, pieces);
            StockLedgerFixture ledger = new StockLedgerFixture(jdbc);
            long expired = ledger.batch(tracked, "E1", LocalDate.of(2020, 1, 1));
            ledger.receive(first, tracked, expired, "5");

            api.post("/api/v1/stock-movements/outbound", """
                    {"productId": %d, "warehouseId": %d, "quantity": 1, "batchNumber": "E1"}""".formatted(tracked, first))
                    .andExpect(problem(422, "BATCH_EXPIRED"));
            api.post("/api/v1/stock-movements/write-off", """
                    {"productId": %d, "warehouseId": %d, "quantity": 5, "batchNumber": "E1", "reason": "Expired"}"""
                    .formatted(tracked, first))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sourceBalanceAfter").value(0));
        }
    }

    @Nested
    class Transfer {

        @Test
        void movesStockBetweenWarehousesInOneMovement() throws Exception {
            api.inbound(product, first, "10").andExpect(status().isCreated());

            api.transfer(product, first, second, "4")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movementType").value("TRANSFER"))
                    .andExpect(jsonPath("$.sourceBalanceAfter").value(6))
                    .andExpect(jsonPath("$.destinationBalanceAfter").value(4));
            assertThat(balance(first)).isEqualByComparingTo("6");
            assertThat(balance(second)).isEqualByComparingTo("4");
        }

        @Test
        void warehouseCannotTransferToItself() throws Exception {
            api.transfer(product, first, first, "1")
                    .andExpect(problem(400, "VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("differentWarehouses"));
        }

        @Test
        void failedTransferChangesNothing() throws Exception {
            // The destination has the lower id, so its balance is incremented before the source check fails:
            // the rollback must undo that increment.
            api.inbound(product, second, "3").andExpect(status().isCreated());

            api.transfer(product, second, first, "5").andExpect(problem(422, "INSUFFICIENT_STOCK"));

            assertThat(balance(first)).isZero();
            assertThat(balance(second)).isEqualByComparingTo("3");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM inventory_balance WHERE warehouse_id = ? AND product_id = ?",
                    Integer.class, first, product)).isZero();
            assertThat(movementCount()).isEqualTo(1);
        }
    }

    @Nested
    class AdjustmentAndWriteOff {

        @Test
        void administratorAdjustsInBothDirections() throws Exception {
            adjust("INCREASE", "8", "Found on recount").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movementType").value("ADJUSTMENT"))
                    .andExpect(jsonPath("$.destinationWarehouse.id").value(first));
            adjust("DECREASE", "3", "Broken during count").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.sourceWarehouse.id").value(first))
                    .andExpect(jsonPath("$.sourceBalanceAfter").value(5));
            adjust("DECREASE", "6", "Too much").andExpect(problem(422, "INSUFFICIENT_STOCK"));
        }

        @Test
        void adjustmentNeedsAPositiveQuantityAndAReason() throws Exception {
            adjust("INCREASE", "0", "Zero").andExpect(problem(400, "VALIDATION_FAILED"));
            adjust("INCREASE", "-2", "Negative").andExpect(problem(400, "VALIDATION_FAILED"));
            adjust("INCREASE", "2", " ").andExpect(problem(400, "VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("reason"));
        }

        @Test
        void managersCannotAdjust() throws Exception {
            String manager = managerToken(first);

            mockMvc.perform(post("/api/v1/stock-movements/adjustment").header(HttpHeaders.AUTHORIZATION, manager)
                            .contentType(MediaType.APPLICATION_JSON).content(adjustmentJson("INCREASE", "1", "Mine")))
                    .andExpect(problem(403, "ACCESS_DENIED"));
        }

        @Test
        void writeOffNeedsStockAndAReason() throws Exception {
            api.inbound(product, first, "4").andExpect(status().isCreated());

            writeOff("1", "Damaged pallet").andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movementType").value("WRITE_OFF"))
                    .andExpect(jsonPath("$.reason").value("Damaged pallet"));
            writeOff("9", "Too much").andExpect(problem(422, "INSUFFICIENT_STOCK"));
            writeOff("1", "").andExpect(problem(400, "VALIDATION_FAILED"));
            assertThat(balance(first)).isEqualByComparingTo("3");
        }
    }

    @Nested
    class AccessAndHistory {

        @Test
        void usersCannotMoveStockAndManagersOnlyInTheirWarehouses() throws Exception {
            String username = unique("ro");
            registerUser(username);
            mockMvc.perform(post("/api/v1/stock-movements/inbound")
                            .header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(username, USER_PASSWORD)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": %d, \"warehouseId\": %d, \"quantity\": 1}".formatted(product, first)))
                    .andExpect(problem(403, "ACCESS_DENIED"));

            String manager = managerToken(first);
            mockMvc.perform(post("/api/v1/stock-movements/inbound").header(HttpHeaders.AUTHORIZATION, manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": %d, \"warehouseId\": %d, \"quantity\": 2}".formatted(product, first)))
                    .andExpect(status().isCreated());
            mockMvc.perform(post("/api/v1/stock-movements/inbound").header(HttpHeaders.AUTHORIZATION, manager)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\": %d, \"warehouseId\": %d, \"quantity\": 2}".formatted(product, second)))
                    .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        }

        @Test
        void historyCanBeFilteredAndIsScoped() throws Exception {
            api.inbound(product, first, "10").andExpect(status().isCreated());
            api.inbound(product, second, "5").andExpect(status().isCreated());
            api.outbound(product, first, "2").andExpect(status().isCreated());
            long transferId = ApiFixtures.id(api.transfer(product, first, second, "3").andExpect(status().isCreated()));
            String today = LocalDate.now().toString();

            api.get("/api/v1/stock-movements/product/" + product)
                    .andExpect(jsonPath("$.totalElements").value(4))
                    .andExpect(jsonPath("$.content[0].id").value(transferId));
            api.get("/api/v1/stock-movements?productId=%d&type=INBOUND".formatted(product))
                    .andExpect(jsonPath("$.totalElements").value(2));
            api.get("/api/v1/stock-movements?productId=%d&from=%s&to=%s&sort=quantity,asc".formatted(product, today, today))
                    .andExpect(jsonPath("$.content[*].quantity").value(org.hamcrest.Matchers.contains(2.0, 3.0, 5.0, 10.0)));
            api.get("/api/v1/stock-movements?productId=%d&from=2000-01-01&to=2000-12-31".formatted(product))
                    .andExpect(jsonPath("$.totalElements").value(0));
            api.get("/api/v1/stock-movements?from=2030-01-02&to=2030-01-01").andExpect(problem(400, "INVALID_PARAMETER"));
            api.get("/api/v1/stock-movements/warehouse/" + second).andExpect(status().isOk());
            api.get("/api/v1/stock-movements/transfer?size=100").andExpect(jsonPath("$.content[*].movementType", everyItem(is("TRANSFER"))));
            api.get("/api/v1/stock-movements/outbound?size=100").andExpect(jsonPath("$.content[*].movementType", everyItem(is("OUTBOUND"))));
            api.get("/api/v1/stock-movements/inbound?size=100").andExpect(jsonPath("$.content[*].movementType", everyItem(is("INBOUND"))));
            api.get("/api/v1/stock-movements/" + transferId).andExpect(jsonPath("$.sourceWarehouse.id").value(first));

            String secondOnly = managerToken(second);
            mockMvc.perform(get("/api/v1/stock-movements?productId=" + product).header(HttpHeaders.AUTHORIZATION, secondOnly))
                    .andExpect(jsonPath("$.totalElements").value(2));
            long outboundFromFirst = JsonPath.<Number>read(api.get("/api/v1/stock-movements?productId=%d&type=OUTBOUND".formatted(product))
                    .andReturn().getResponse().getContentAsString(), "$.content[0].id").longValue();
            mockMvc.perform(get("/api/v1/stock-movements/" + outboundFromFirst).header(HttpHeaders.AUTHORIZATION, secondOnly))
                    .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        }

        @Test
        void historyHasNoUpdateOrDeleteEndpoints() throws Exception {
            long id = ApiFixtures.id(api.inbound(product, first, "1").andExpect(status().isCreated()));
            String admin = bearer(adminToken());

            mockMvc.perform(put("/api/v1/stock-movements/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(problem(405, "METHOD_NOT_ALLOWED"));
            mockMvc.perform(patch("/api/v1/stock-movements/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(problem(405, "METHOD_NOT_ALLOWED"));
            mockMvc.perform(delete("/api/v1/stock-movements/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                    .andExpect(problem(405, "METHOD_NOT_ALLOWED"));
        }

        @Test
        void balancesAlwaysEqualTheLedger() throws Exception {
            api.inbound(product, first, "20").andExpect(status().isCreated());
            api.transfer(product, first, second, "7").andExpect(status().isCreated());
            api.outbound(product, second, "2").andExpect(status().isCreated());
            writeOff("1", "Scratched").andExpect(status().isCreated());
            api.outbound(product, first, "100").andExpect(problem(422, "INSUFFICIENT_STOCK"));

            for (long warehouse : new long[] {first, second}) {
                assertThat(balance(warehouse)).isEqualByComparingTo(ledgerTotal(warehouse));
            }
            assertThat(balance(first)).isEqualByComparingTo("12");
            assertThat(balance(second)).isEqualByComparingTo("5");
        }
    }

    private org.springframework.test.web.servlet.ResultActions adjust(String direction, String quantity, String reason)
            throws Exception {
        return api.post("/api/v1/stock-movements/adjustment", adjustmentJson(direction, quantity, reason));
    }

    private String adjustmentJson(String direction, String quantity, String reason) {
        return """
                {"productId": %d, "warehouseId": %d, "direction": "%s", "quantity": %s, "reason": "%s"}"""
                .formatted(product, first, direction, quantity, reason);
    }

    private org.springframework.test.web.servlet.ResultActions writeOff(String quantity, String reason) throws Exception {
        return api.post("/api/v1/stock-movements/write-off", """
                {"productId": %d, "warehouseId": %d, "quantity": %s, "reason": "%s"}"""
                .formatted(product, first, quantity, reason));
    }

    private String managerToken(long warehouse) throws Exception {
        String username = unique("mgr");
        long id = registerUser(username);
        api.makeWarehouseManager(id);
        api.assignManager(warehouse, id).andExpect(status().isOk());
        return bearer(tokenFor(username, USER_PASSWORD));
    }

    private BigDecimal balance(long warehouse) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_balance WHERE warehouse_id = ? AND product_id = ?""",
                BigDecimal.class, warehouse, product);
    }

    private BigDecimal ledgerTotal(long warehouse) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN destination_warehouse_id = ? THEN quantity ELSE 0 END), 0)
                     - COALESCE(SUM(CASE WHEN source_warehouse_id = ? THEN quantity ELSE 0 END), 0)
                FROM stock_movement WHERE product_id = ?""", BigDecimal.class, warehouse, warehouse, product);
    }

    private int movementCount() {
        return jdbc.queryForObject("SELECT count(*) FROM stock_movement WHERE product_id = ?", Integer.class, product);
    }

    private static String code() {
        return unique("SM").replace("-", "");
    }
}
