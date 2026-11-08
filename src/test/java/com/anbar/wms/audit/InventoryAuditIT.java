package com.anbar.wms.audit;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.audit.service.InventoryAuditService;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.AuthenticatedUserResolver;
import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import com.anbar.wms.support.Concurrently;
import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class InventoryAuditIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InventoryAuditService audits;

    @Autowired
    private AuthenticatedUserResolver users;

    private ApiFixtures api;
    private long pieces;
    private long category;
    private long product;
    private long warehouse;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        category = api.category(unique("Audit"));
        product = api.product(unique("aud"), "Audited", category, pieces, "0");
        warehouse = api.warehouse(unique("AU").replace("-", ""));
        api.inbound(product, warehouse, "10").andExpect(status().isCreated());
    }

    @Test
    void countedShortageIsPostedAsAnAdjustmentOnCompletion() throws Exception {
        long audit = startedAudit(false);
        long item = itemId(audit, 0);

        count(audit, item, "8").andExpect(jsonPath("$.items[0].expectedQuantity").value(10))
                .andExpect(jsonPath("$.items[0].discrepancy").value(-2))
                .andExpect(jsonPath("$.discrepancyItems").value(1));
        api.patch(path(audit, "submit"), null).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        api.patch(path(audit, "approve"), null).andExpect(jsonPath("$.status").value("APPROVED"));
        String number = JsonPath.read(api.patch(path(audit, "complete"), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completed.at").isString())
                .andReturn().getResponse().getContentAsString(), "$.adjustmentTransactionNumber");

        assertThat(balance()).isEqualByComparingTo("8");
        assertThat(jdbc.queryForObject("""
                SELECT m.quantity FROM stock_movement m JOIN stock_transaction t ON t.id = m.transaction_id
                WHERE t.transaction_number = ? AND m.movement_type = 'ADJUSTMENT' AND m.source_warehouse_id = ?""",
                BigDecimal.class, number, warehouse)).isEqualByComparingTo("2");
        api.patch(path(audit, "complete"), null).andExpect(problem(409, "INVALID_STATE_TRANSITION"));
        assertThat(balance()).isEqualByComparingTo("8");
    }

    @Test
    void surplusIncreasesStockAndMatchingCountsPostNothing() throws Exception {
        long other = api.product(unique("aud"), "Exact", category, pieces, "0");
        api.inbound(other, warehouse, "5").andExpect(status().isCreated());
        long audit = draft(false);
        addItem(audit, product).andExpect(status().isOk());
        addItem(audit, other).andExpect(status().isOk());
        api.patch(path(audit, "start"), null).andExpect(status().isOk());

        count(audit, itemId(audit, 0), "12").andExpect(status().isOk());
        count(audit, itemId(audit, 1), "5").andExpect(status().isOk());
        approveAndComplete(audit).andExpect(status().isOk());

        assertThat(balance()).isEqualByComparingTo("12");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM stock_movement m JOIN stock_transaction t ON t.id = m.transaction_id
                JOIN inventory_audit a ON a.adjustment_transaction_id = t.id WHERE a.id = ?""", Long.class, audit))
                .isEqualTo(1L);
    }

    @Test
    void movementsAfterTheCountAreKeptAndShown() throws Exception {
        long audit = startedAudit(false);
        long item = itemId(audit, 0);
        count(audit, item, "8").andExpect(status().isOk());
        api.outbound(product, warehouse, "3").andExpect(status().isCreated());

        api.get("/api/v1/inventory-audits/" + audit)
                .andExpect(jsonPath("$.items[0].movementsSinceCount").value(1))
                .andExpect(jsonPath("$.items[0].netChangeSinceCount").value(-3));
        approveAndComplete(audit).andExpect(status().isOk());

        assertThat(balance()).isEqualByComparingTo("5");
    }

    @Test
    void completionFailsWithoutChangesWhenStockLeftAfterTheCount() throws Exception {
        long audit = startedAudit(false);
        count(audit, itemId(audit, 0), "1").andExpect(status().isOk());
        api.outbound(product, warehouse, "5").andExpect(status().isCreated());

        approveAndComplete(audit).andExpect(problem(422, "INSUFFICIENT_STOCK"));

        api.get("/api/v1/inventory-audits/" + audit).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(balance()).isEqualByComparingTo("5");
    }

    @Test
    void concurrentCompletionsPostTheAdjustmentOnce() throws Exception {
        long audit = startedAudit(false);
        count(audit, itemId(audit, 0), "7").andExpect(status().isOk());
        api.patch(path(audit, "submit"), null).andExpect(status().isOk());
        api.patch(path(audit, "approve"), null).andExpect(status().isOk());
        AuthenticatedUser admin = users.resolve(jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, ADMIN_USERNAME)).orElseThrow();

        List<Exception> outcomes = Concurrently.run(6, 6, i -> () -> audits.complete(audit, admin));

        assertThat(outcomes.stream().filter(Objects::isNull)).hasSize(1);
        assertThat(outcomes.stream().filter(Objects::nonNull)).allSatisfy(e -> assertThat(e)
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION)));
        assertThat(balance()).isEqualByComparingTo("7");
    }

    @Test
    void blindAuditsHideSystemQuantitiesUntilSubmitted() throws Exception {
        long audit = startedAudit(true);
        long item = itemId(audit, 0);

        count(audit, item, "9")
                .andExpect(jsonPath("$.items[0].countedQuantity").value(9))
                .andExpect(jsonPath("$.items[0].expectedQuantity").doesNotExist())
                .andExpect(jsonPath("$.items[0].discrepancy").doesNotExist());
        api.patch(path(audit, "submit"), null)
                .andExpect(jsonPath("$.items[0].expectedQuantity").value(10))
                .andExpect(jsonPath("$.items[0].discrepancy").value(-1));
    }

    @Test
    void workflowRulesAreEnforced() throws Exception {
        long audit = draft(false);
        api.patch(path(audit, "start"), null).andExpect(problem(422, "BUSINESS_RULE_VIOLATION"));
        addItem(audit, product).andExpect(status().isOk());
        addItem(audit, product).andExpect(problem(409, "DUPLICATE_RESOURCE"));
        long item = itemId(audit, 0);
        count(audit, item, "1").andExpect(problem(409, "INVALID_STATE_TRANSITION"));

        api.patch(path(audit, "start"), null).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        api.patch(path(audit, "submit"), null).andExpect(problem(422, "BUSINESS_RULE_VIOLATION"));
        count(audit, item, "-1").andExpect(problem(400, "VALIDATION_FAILED"));
        count(audit, item, "1.5").andExpect(problem(400, "INVALID_QUANTITY_SCALE"));
        count(audit, item, "10").andExpect(status().isOk());
        api.patch(path(audit, "submit"), null).andExpect(status().isOk());

        api.patch(path(audit, "reject"), "{\"reason\": \"Recount\"}")
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.rejectionReason").value("Recount"));
        api.patch(path(audit, "cancel"), null).andExpect(jsonPath("$.status").value("CANCELLED"));
        api.patch(path(audit, "start"), null).andExpect(problem(409, "INVALID_STATE_TRANSITION"));
        assertThat(balance()).isEqualByComparingTo("10");
    }

    @Test
    void fullAuditCoversStockedItemsAndOnlyOneMayBeOpen() throws Exception {
        long lot = api.batchProduct(unique("lot"), category, pieces);
        inboundBatch(lot, "A1", "3");
        inboundBatch(lot, "A2", "4");

        long full = ApiFixtures.id(api.post("/api/v1/inventory-audits",
                "{\"warehouseId\": %d, \"fullAudit\": true}".formatted(warehouse)).andExpect(status().isCreated()));
        api.get("/api/v1/inventory-audits/" + full)
                .andExpect(jsonPath("$.scope").value("FULL"))
                .andExpect(jsonPath("$.itemCount").value(3))
                .andExpect(jsonPath("$.items[*].batch.batchNumber").value(hasItem("A2")));
        api.patch(path(full, "start"), null).andExpect(status().isOk());

        long second = ApiFixtures.id(api.post("/api/v1/inventory-audits",
                "{\"warehouseId\": %d, \"fullAudit\": true}".formatted(warehouse)).andExpect(status().isCreated()));
        api.patch(path(second, "start"), null).andExpect(problem(409, "INVALID_STATE_TRANSITION"));
    }

    @Test
    void batchProductWithoutBatchAddsEachStockedBatch() throws Exception {
        long lot = api.batchProduct(unique("lot"), category, pieces);
        inboundBatch(lot, "B1", "2");
        inboundBatch(lot, "B2", "6");
        long audit = draft(false);

        addItem(audit, lot)
                .andExpect(jsonPath("$.itemCount").value(2))
                .andExpect(jsonPath("$.items[*].batch.batchNumber").value(containsInAnyOrder("B1", "B2")));
        api.post(path(audit, "items"), "{\"items\": [{\"productId\": %d, \"batchNumber\": \"NOPE\"}]}".formatted(lot))
                .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        api.post(path(audit, "items"), "{\"items\": [{\"productId\": %d, \"batchNumber\": \"X\"}]}".formatted(product))
                .andExpect(problem(422, "BATCH_NOT_ALLOWED"));
    }

    @Test
    void reportsShowDiscrepanciesAndStatistics() throws Exception {
        long audit = startedAudit(false);
        count(audit, itemId(audit, 0), "6").andExpect(status().isOk());
        approveAndComplete(audit).andExpect(status().isOk());

        api.get("/api/v1/inventory-audits/discrepancies?warehouseId=" + warehouse)
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].discrepancy").value(-4))
                .andExpect(jsonPath("$.content[0].auditStatus").value("COMPLETED"));
        api.get("/api/v1/inventory-audits/statistics?warehouseId=" + warehouse)
                .andExpect(jsonPath("$.audits").value(1))
                .andExpect(jsonPath("$.auditsByStatus.COMPLETED").value(1))
                .andExpect(jsonPath("$.countedItems").value(1))
                .andExpect(jsonPath("$.accuracyPercent").value(0.0))
                .andExpect(jsonPath("$.shortageQuantity").value(4));
        api.get("/api/v1/inventory-audits/statistics/by-warehouse?from=" + LocalDate.now().minusDays(1))
                .andExpect(jsonPath("$[?(@.warehouse.id == %d)].completedAudits".formatted(warehouse)).value(hasItem(1)));
        api.get("/api/v1/inventory-audits?status=COMPLETED&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[0].id").value(audit))
                .andExpect(jsonPath("$.content[0].items").doesNotExist());
    }

    @Test
    void rolesAndWarehouseScopeAreEnforced() throws Exception {
        long audit = startedAudit(false);
        count(audit, itemId(audit, 0), "10").andExpect(status().isOk());
        api.patch(path(audit, "submit"), null).andExpect(status().isOk());

        String plain = unique("viewer");
        registerUser(plain);
        String userToken = tokenFor(plain, USER_PASSWORD);
        mockMvc.perform(post("/api/v1/inventory-audits").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"warehouseId\": %d}".formatted(warehouse)))
                .andExpect(problem(403, "ACCESS_DENIED"));

        String managerName = unique("mgr");
        long manager = registerUser(managerName);
        api.makeWarehouseManager(manager);
        String managerToken = tokenFor(managerName, USER_PASSWORD);
        mockMvc.perform(patch(path(audit, "approve")).header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(problem(403, "ACCESS_DENIED"));
        mockMvc.perform(patch(path(audit, "cancel")).header(HttpHeaders.AUTHORIZATION, bearer(managerToken)))
                .andExpect(problem(404, "RESOURCE_NOT_FOUND"));
    }

    private long startedAudit(boolean blind) throws Exception {
        long audit = draft(blind);
        addItem(audit, product).andExpect(status().isOk());
        api.patch(path(audit, "start"), null).andExpect(status().isOk());
        return audit;
    }

    private long draft(boolean blind) throws Exception {
        return ApiFixtures.id(api.post("/api/v1/inventory-audits",
                        "{\"warehouseId\": %d, \"blind\": %s}".formatted(warehouse, blind))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT")));
    }

    private ResultActions addItem(long audit, long productId) throws Exception {
        return api.post(path(audit, "items"), "{\"items\": [{\"productId\": %d}]}".formatted(productId));
    }

    private ResultActions count(long audit, long item, String quantity) throws Exception {
        return api.put("/api/v1/inventory-audits/%d/items/%d/count".formatted(audit, item),
                "{\"countedQuantity\": %s}".formatted(quantity));
    }

    private ResultActions approveAndComplete(long audit) throws Exception {
        api.patch(path(audit, "submit"), null).andExpect(status().isOk());
        api.patch(path(audit, "approve"), null).andExpect(status().isOk());
        return api.patch(path(audit, "complete"), null);
    }

    private void inboundBatch(long productId, String batch, String quantity) throws Exception {
        api.post("/api/v1/stock-movements/inbound", """
                {"productId": %d, "warehouseId": %d, "quantity": %s, "batchNumber": "%s", "expiresOn": "%s"}"""
                .formatted(productId, warehouse, quantity, batch, LocalDate.now().plusMonths(3)))
                .andExpect(status().isCreated());
    }

    private long itemId(long audit, int index) throws Exception {
        String body = api.get("/api/v1/inventory-audits/" + audit).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.items[%d].id".formatted(index))).longValue();
    }

    private static String path(long audit, String action) {
        return "/api/v1/inventory-audits/%d/%s".formatted(audit, action);
    }

    private BigDecimal balance() {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_balance WHERE warehouse_id = ? AND product_id = ?""",
                BigDecimal.class, warehouse, product);
    }
}
