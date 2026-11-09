package com.anbar.wms.inventory;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.common.exception.InsufficientStockException;
import com.anbar.wms.inventory.dto.StockMovementRequests;
import com.anbar.wms.inventory.service.StockMovementService;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.AuthenticatedUserResolver;
import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import com.anbar.wms.support.Concurrently;
import com.anbar.wms.support.StockLedgerFixture;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class BatchAndExpirationIT extends AbstractIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Asia/Baku"));

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StockMovementService movements;

    @Autowired
    private AuthenticatedUserResolver users;

    private ApiFixtures api;
    private long product;
    private long warehouse;
    private long expired;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        long pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        product = api.batchProduct(unique("fefo"), api.category(unique("Fefo")), pieces);
        warehouse = api.warehouse(unique("FE").replace("-", ""));
        receive("LATE", TODAY.plusDays(60), "10");
        receive("SOON", TODAY.plusDays(10), "4");
        receive("NEXT", TODAY.plusDays(20), "5");
        StockLedgerFixture ledger = new StockLedgerFixture(jdbc);
        expired = ledger.batch(product, "OLD", TODAY.minusDays(3));
        ledger.receive(warehouse, product, expired, "7");
    }

    @Test
    void listsBatchStockByExpiry() throws Exception {
        api.get("/api/v1/batches?productId=" + product)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].batchNumber").value(contains("OLD", "SOON", "NEXT", "LATE")))
                .andExpect(jsonPath("$.content[0].expired").value(true))
                .andExpect(jsonPath("$.content[0].daysUntilExpiration").value(-3))
                .andExpect(jsonPath("$.content[1].initialQuantity").value(4))
                .andExpect(jsonPath("$.content[1].availableQuantity").value(4));
        api.get("/api/v1/batches?productId=%d&batchNumber=so".formatted(product))
                .andExpect(jsonPath("$.totalElements").value(1));
        api.get("/api/v1/batches/warehouse/" + warehouse).andExpect(jsonPath("$.totalElements").value(4));
        api.get("/api/v1/batches?productId=%d&sort=availableQuantity,desc".formatted(product))
                .andExpect(jsonPath("$.content[0].batchNumber").value("LATE"));
        api.get("/api/v1/batches?sort=secret").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    @Test
    void outboundWithoutBatchTakesEarliestUsableBatchesFirst() throws Exception {
        api.outbound(product, warehouse, "6")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quantity").value(6))
                .andExpect(jsonPath("$.allocations[*].batch.batchNumber").value(contains("SOON", "NEXT")))
                .andExpect(jsonPath("$.allocations[0].quantity").value(4))
                .andExpect(jsonPath("$.allocations[1].quantity").value(2))
                .andExpect(jsonPath("$.allocations[1].sourceBalanceAfter").value(3));

        assertThat(batchQuantity("OLD")).isEqualByComparingTo("7");
        assertThat(batchQuantity("SOON")).isZero();
        api.get("/api/v1/batches?productId=" + product)
                .andExpect(jsonPath("$.content[*].batchNumber").value(not(hasItem("SOON"))));
        api.get("/api/v1/batches?includeEmpty=true&productId=" + product)
                .andExpect(jsonPath("$.content[*].batchNumber").value(hasItem("SOON")));
    }

    @Test
    void expiredStockIsNeverPickedForOutbound() throws Exception {
        api.outbound(product, warehouse, "20").andExpect(problem(422, "INSUFFICIENT_STOCK"));
        api.outbound(product, warehouse, "19").andExpect(status().isCreated());
        assertThat(batchQuantity("OLD")).isEqualByComparingTo("7");
    }

    @Test
    void writeOffMayTakeExpiredStock() throws Exception {
        api.post("/api/v1/stock-movements/write-off", """
                {"productId": %d, "warehouseId": %d, "quantity": 9, "reason": "Damaged"}""".formatted(product, warehouse))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.allocations[0].batch.batchNumber").value("OLD"));
        assertThat(batchQuantity("OLD")).isZero();
        assertThat(batchQuantity("SOON")).isEqualByComparingTo("2");
    }

    @Test
    void reportsExpiredAndExpiringBatches() throws Exception {
        api.get("/api/v1/batches/expired?warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[*].batchNumber").value(contains("OLD")));
        api.get("/api/v1/batches/expiring?days=7&warehouseId=" + warehouse).andExpect(jsonPath("$.totalElements").value(0));
        api.get("/api/v1/batches/expiring?days=30&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[*].batchNumber").value(contains("SOON", "NEXT")));
        api.get("/api/v1/batches/expiring?days=-1").andExpect(problem(400, "INVALID_PARAMETER"));
        api.get("/api/v1/batches/expiration-risk?days=30&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[0].product.id").value(product))
                .andExpect(jsonPath("$.content[0].expiredQuantity").value(7))
                .andExpect(jsonPath("$.content[0].expiringQuantity").value(9))
                .andExpect(jsonPath("$.content[0].batchCount").value(3));
    }

    @Test
    void batchDetailAndHistory() throws Exception {
        api.outbound(product, warehouse, "1").andExpect(status().isCreated());
        long soon = jdbc.queryForObject("SELECT id FROM stock_batch WHERE product_id = ? AND batch_number = 'SOON'",
                Long.class, product);

        api.get("/api/v1/batches/" + soon)
                .andExpect(jsonPath("$.content[0].availableQuantity").value(3))
                .andExpect(jsonPath("$.content[0].initialQuantity").value(4));
        api.get("/api/v1/batches/" + soon + "/movements")
                .andExpect(jsonPath("$.content[*].movementType").value(contains("OUTBOUND", "INBOUND")));
        api.get("/api/v1/batches/999999999").andExpect(problem(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void concurrentFefoWithdrawalsNeverOversellABatch() throws Exception {
        AuthenticatedUser admin = users.resolve(jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, ADMIN_USERNAME)).orElseThrow();

        List<Exception> outcomes = Concurrently.run(30, 12, i -> () -> movements.outbound(
                new StockMovementRequests.Outbound(product, warehouse, BigDecimal.ONE, null, null, null), admin));

        assertThat(outcomes.stream().filter(Objects::isNull)).hasSize(19);
        assertThat(outcomes.stream().filter(e -> e instanceof InsufficientStockException)).hasSize(11);
        assertThat(batchQuantity("SOON")).isZero();
        assertThat(batchQuantity("NEXT")).isZero();
        assertThat(batchQuantity("LATE")).isZero();
        assertThat(batchQuantity("OLD")).isEqualByComparingTo("7");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM inventory_balance WHERE warehouse_id = ? AND (quantity < 0 OR quantity > total_received)""",
                Long.class, warehouse)).isZero();
    }

    @Test
    void reusingABatchNumberWithADifferentDateIsRejected() throws Exception {
        receive("DUP", TODAY.plusDays(30), "5");

        // Same number, contradictory expiry → 409; the same date is accepted and tops the lot up.
        api.post("/api/v1/stock-movements/inbound", """
                {"productId": %d, "warehouseId": %d, "quantity": 1, "batchNumber": "DUP", "expiresOn": "%s"}"""
                .formatted(product, warehouse, TODAY.plusDays(40)))
                .andExpect(problem(409, "BATCH_METADATA_MISMATCH"));
        receive("DUP", TODAY.plusDays(30), "2");
        assertThat(batchQuantity("DUP")).isEqualByComparingTo("7");
    }

    @Test
    void aFutureProductionDateIsRejected() throws Exception {
        api.post("/api/v1/stock-movements/inbound", """
                {"productId": %d, "warehouseId": %d, "quantity": 1, "batchNumber": "FUT",
                 "expiresOn": "%s", "manufacturedOn": "%s"}"""
                .formatted(product, warehouse, TODAY.plusDays(90), TODAY.plusDays(1)))
                .andExpect(problem(400, "VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("producedOn"));
    }

    private void receive(String batchNumber, LocalDate expiresOn, String quantity) throws Exception {
        api.post("/api/v1/stock-movements/inbound", """
                {"productId": %d, "warehouseId": %d, "quantity": %s, "batchNumber": "%s", "expiresOn": "%s"}"""
                .formatted(product, warehouse, quantity, batchNumber, expiresOn)).andExpect(status().isCreated());
    }

    private BigDecimal batchQuantity(String batchNumber) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(b.quantity), 0) FROM inventory_balance b JOIN stock_batch s ON s.id = b.batch_id
                WHERE b.warehouse_id = ? AND s.product_id = ? AND s.batch_number = ?""",
                BigDecimal.class, warehouse, product, batchNumber);
    }
}
