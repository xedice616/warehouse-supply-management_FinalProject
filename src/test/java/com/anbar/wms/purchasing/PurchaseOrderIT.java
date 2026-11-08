package com.anbar.wms.purchasing;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.purchasing.dto.PurchaseOrderRequests;
import com.anbar.wms.purchasing.service.PurchaseReceivingService;
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
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class PurchaseOrderIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PurchaseReceivingService receiving;

    @Autowired
    private AuthenticatedUserResolver users;

    private ApiFixtures api;
    private String token;
    private long supplier;
    private long warehouse;
    private long bolts;
    private long nuts;
    private long pieces;
    private long category;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        api = new ApiFixtures(mockMvc, token);
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        category = api.category(unique("Purchasing"));
        bolts = api.product(unique("bolt"), "Bolt", category, pieces, "0");
        nuts = api.product(unique("nut"), "Nut", category, pieces, "0");
        supplier = api.supplier(unique("Acme"), "%010d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        warehouse = api.warehouse(unique("PO").replace("-", ""));
    }

    @Test
    void draftIsCreatedWithAServerSideNumberAndTotal() throws Exception {
        api.post("/api/v1/purchase-orders", createJson(warehouse))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderNumber").value(matchesPattern("PO-\\d{4}-\\d{6}")))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.currency").value("AZN"))
                .andExpect(jsonPath("$.totalAmount").value(65.0))
                .andExpect(jsonPath("$.items[0].outstandingQuantity").value(10));
    }

    @Test
    void draftCanBeEditedButNotOnceSubmitted() throws Exception {
        long id = ApiFixtures.id(api.post("/api/v1/purchase-orders", createJson(warehouse)));
        long created = version(id);

        api.put("/api/v1/purchase-orders/" + id, updateJson(created))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.totalAmount").value(30.0));
        api.put("/api/v1/purchase-orders/" + id, updateJson(created)).andExpect(problem(409, "CONCURRENT_MODIFICATION"));
        api.patch("/api/v1/purchase-orders/" + id + "/submit", null).andExpect(status().isOk());
        api.put("/api/v1/purchase-orders/" + id, updateJson(version(id))).andExpect(problem(409, "INVALID_STATE_TRANSITION"));
    }

    private long version(long orderId) throws Exception {
        return ((Number) JsonPath.read(api.get("/api/v1/purchase-orders/" + orderId).andReturn().getResponse()
                .getContentAsString(), "$.version")).longValue();
    }

    @Test
    void rejectionSendsTheOrderBackToDraft() throws Exception {
        long id = ApiFixtures.id(api.post("/api/v1/purchase-orders", createJson(warehouse)));
        api.patch("/api/v1/purchase-orders/" + id + "/submit", null).andExpect(status().isOk());

        api.patch("/api/v1/purchase-orders/" + id + "/reject", "{\"reason\": \"Prices too high\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rejectionReason").value("Prices too high"));
        api.patch("/api/v1/purchase-orders/" + id + "/approve", null).andExpect(problem(409, "INVALID_STATE_TRANSITION"));
    }

    @Test
    void partialAndFinalReceiptsPostInboundStock() throws Exception {
        long id = approvedOrder();
        long boltLine = itemId(id, 0);
        long nutLine = itemId(id, 1);

        receive(id, UUID.randomUUID().toString(), receiptJson(boltLine, "4"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("PARTIALLY_RECEIVED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.movement.movementType").value("INBOUND"))
                .andExpect(jsonPath("$.movement.supplier.id").value(supplier));
        assertThat(balance(bolts)).isEqualByComparingTo("4");

        api.get("/api/v1/purchase-orders?status=PARTIALLY_RECEIVED&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)));
        api.get("/api/v1/purchase-orders?outstanding=true&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[*].id").value(hasItem((int) id)));

        receive(id, UUID.randomUUID().toString(), """
                {"items": [{"itemId": %d, "quantity": 6}, {"itemId": %d, "quantity": 4}]}""".formatted(boltLine, nutLine))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderStatus").value("RECEIVED"))
                .andExpect(jsonPath("$.movement.allocations.length()").value(2));
        assertThat(balance(bolts)).isEqualByComparingTo("10");
        assertThat(balance(nuts)).isEqualByComparingTo("4");

        receive(id, UUID.randomUUID().toString(), receiptJson(boltLine, "1"))
                .andExpect(problem(409, "INVALID_STATE_TRANSITION"));
    }

    @Test
    void repeatingAReceiptWithTheSameKeyReturnsTheOriginal() throws Exception {
        long id = approvedOrder();
        String key = UUID.randomUUID().toString();
        String body = receiptJson(itemId(id, 0), "3");

        long receiptId = receiptId(receive(id, key, body).andExpect(status().isCreated()));
        receive(id, key, body)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.receiptId").value(receiptId));

        assertThat(balance(bolts)).isEqualByComparingTo("3");
        api.get("/api/v1/purchase-orders/" + id).andExpect(jsonPath("$.items[0].receivedQuantity").value(3));
    }

    @Test
    void aKeyCannotBeReusedForAnotherOrder() throws Exception {
        long first = approvedOrder();
        long second = approvedOrder();
        String key = UUID.randomUUID().toString();
        receive(first, key, receiptJson(itemId(first, 0), "1")).andExpect(status().isCreated());

        receive(second, key, receiptJson(itemId(second, 0), "1")).andExpect(problem(409, "IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void concurrentRetriesOfOneReceiptReceiveOnce() throws Exception {
        long id = approvedOrder();
        long line = itemId(id, 0);
        AuthenticatedUser admin = users.resolve(jdbc.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, ADMIN_USERNAME)).orElseThrow();
        PurchaseOrderRequests.Receipt request = new PurchaseOrderRequests.Receipt(List.of(
                new PurchaseOrderRequests.ReceiptItem(line, new BigDecimal("5"), null, null, null)));
        String key = UUID.randomUUID().toString();

        List<Exception> outcomes = Concurrently.run(8, 8, i -> () -> receiving.receive(id, key, request, admin));

        assertThat(outcomes).allMatch(Objects::isNull);
        assertThat(balance(bolts)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goods_receipt WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1L);
    }

    @Test
    void receiptsAreValidatedBeforeAnythingChanges() throws Exception {
        long id = approvedOrder();
        long line = itemId(id, 0);

        mockMvc.perform(post("/api/v1/purchase-orders/" + id + "/receipts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON).content(receiptJson(line, "1")))
                .andExpect(problem(400, "IDEMPOTENCY_KEY_REQUIRED"));
        receive(id, UUID.randomUUID().toString(), receiptJson(line, "11")).andExpect(problem(422, "RECEIPT_EXCEEDS_ORDER"));
        receive(id, UUID.randomUUID().toString(), receiptJson(line, "0")).andExpect(problem(400, "VALIDATION_FAILED"));

        assertThat(balance(bolts)).isZero();
    }

    @Test
    void onlyApprovedOrdersReceive() throws Exception {
        long draft = ApiFixtures.id(api.post("/api/v1/purchase-orders", createJson(warehouse)));
        receive(draft, UUID.randomUUID().toString(), receiptJson(itemId(draft, 0), "1"))
                .andExpect(problem(409, "INVALID_STATE_TRANSITION"));

        long cancelled = approvedOrder();
        api.patch("/api/v1/purchase-orders/" + cancelled + "/cancel", "{\"reason\": \"No longer needed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        receive(cancelled, UUID.randomUUID().toString(), receiptJson(itemId(cancelled, 0), "1"))
                .andExpect(problem(409, "INVALID_STATE_TRANSITION"));
        assertThat(balance(bolts)).isZero();
    }

    @Test
    void receivingABatchProductCreatesTheBatchWithCostAndOrderLink() throws Exception {
        long lot = api.batchProduct(unique("lot"), category, pieces);
        long id = ApiFixtures.id(api.post("/api/v1/purchase-orders", """
                {"supplierId": %d, "warehouseId": %d, "items": [{"productId": %d, "orderedQuantity": 20, "unitPrice": 1.25}]}"""
                .formatted(supplier, warehouse, lot)));
        approve(id);
        long line = itemId(id, 0);
        String batchNumber = unique("B");

        receive(id, UUID.randomUUID().toString(), """
                {"items": [{"itemId": %d, "quantity": 12, "batchNumber": "%s", "expirationDate": "%s"}]}"""
                .formatted(line, batchNumber, LocalDate.now().plusMonths(6)))
                .andExpect(status().isCreated());

        api.get("/api/v1/batches?productId=" + lot)
                .andExpect(jsonPath("$.content[0].batchNumber").value(batchNumber))
                .andExpect(jsonPath("$.content[0].initialQuantity").value(12))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(12))
                .andExpect(jsonPath("$.content[0].unitCost").value(1.25))
                .andExpect(jsonPath("$.content[0].purchaseOrderItemId").value(line));
        receive(id, UUID.randomUUID().toString(), receiptJson(line, "1")).andExpect(problem(422, "BATCH_REQUIRED"));
    }

    @Test
    void summaryGroupsOrderedValueBySupplier() throws Exception {
        LocalDate day = LocalDate.of(2090, 1, 1).plusDays(ThreadLocalRandom.current().nextInt(20_000));
        api.post("/api/v1/purchase-orders", """
                {"supplierId": %d, "warehouseId": %d, "orderDate": "%s",
                 "items": [{"productId": %d, "orderedQuantity": 3, "unitPrice": 5}]}"""
                .formatted(supplier, warehouse, day, bolts)).andExpect(status().isCreated());

        api.get("/api/v1/purchase-orders/summary?groupBy=SUPPLIER&from=%s&to=%s".formatted(day, day))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].key").value(String.valueOf(supplier)))
                .andExpect(jsonPath("$.content[0].orderCount").value(1))
                .andExpect(jsonPath("$.content[0].totalAmount").value(15.0));
        api.get("/api/v1/purchase-orders/summary?groupBy=STATUS&sort=nope").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    @Test
    void ordinaryUsersCannotManageOrders() throws Exception {
        String username = unique("buyer");
        registerUser(username);
        String userToken = tokenFor(username, USER_PASSWORD);

        mockMvc.perform(post("/api/v1/purchase-orders").header(HttpHeaders.AUTHORIZATION, bearer(userToken))
                        .contentType(MediaType.APPLICATION_JSON).content(createJson(warehouse)))
                .andExpect(problem(403, "ACCESS_DENIED"));
    }

    private long approvedOrder() throws Exception {
        long id = ApiFixtures.id(api.post("/api/v1/purchase-orders", createJson(warehouse)).andExpect(status().isCreated()));
        approve(id);
        return id;
    }

    private void approve(long id) throws Exception {
        api.patch("/api/v1/purchase-orders/" + id + "/submit", null).andExpect(status().isOk());
        api.patch("/api/v1/purchase-orders/" + id + "/approve", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    private ResultActions receive(long orderId, String key, String json) throws Exception {
        return mockMvc.perform(post("/api/v1/purchase-orders/" + orderId + "/receipts")
                .header(HttpHeaders.AUTHORIZATION, bearer(token))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long itemId(long orderId, int index) throws Exception {
        String body = api.get("/api/v1/purchase-orders/" + orderId).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.items[%d].id".formatted(index))).longValue();
    }

    private static long receiptId(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.receiptId")).longValue();
    }

    private String createJson(long warehouseId) {
        return """
                {"supplierId": %d, "warehouseId": %d, "items": [
                  {"productId": %d, "orderedQuantity": 10, "unitPrice": 2.50},
                  {"productId": %d, "orderedQuantity": 4, "unitPrice": 10}]}""".formatted(supplier, warehouseId, bolts, nuts);
    }

    private String updateJson(long version) {
        return """
                {"supplierId": %d, "warehouseId": %d, "orderDate": "%s", "version": %d,
                 "items": [{"productId": %d, "orderedQuantity": 12, "unitPrice": 2.50}]}"""
                .formatted(supplier, warehouse, LocalDate.now(), version, bolts);
    }

    private static String receiptJson(long itemId, String quantity) {
        return "{\"items\": [{\"itemId\": %d, \"quantity\": %s}]}".formatted(itemId, quantity);
    }

    private BigDecimal balance(long product) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_balance WHERE warehouse_id = ? AND product_id = ?""",
                BigDecimal.class, warehouse, product);
    }
}
