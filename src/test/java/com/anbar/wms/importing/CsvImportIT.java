package com.anbar.wms.importing;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;

class CsvImportIT extends AbstractIntegrationTest {

    private static final String HEADER = "warehouseCode,sku,quantity,unitCost,batchNumber,expirationDate,referenceNumber";

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;
    private String token;
    private long category;
    private long pieces;
    private String warehouseCode;
    private long warehouseId;
    private String sku;
    private long productId;

    @BeforeEach
    void setUp() throws Exception {
        token = adminToken();
        api = new ApiFixtures(mockMvc, token);
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        category = api.category(unique("Import"));
        warehouseCode = unique("IMP").replace("-", "").toUpperCase();
        warehouseId = api.warehouse(warehouseCode);
        sku = unique("imp").toUpperCase();
        productId = api.product(sku, "Imported widget", category, pieces, "0");
    }

    @Test
    void importsValidRowsAsInboundStock() throws Exception {
        String sku2 = unique("imp2").toUpperCase();
        long product2 = api.product(sku2, "Second widget", category, pieces, "0");
        String csv = HEADER + "\n"
                + row(warehouseCode, sku, "50", "12.50", "", "", "IMPORT-1")
                + row(warehouseCode, sku2, "30", "8.75", "", "", "IMPORT-1");

        upload(csv, UUID.randomUUID().toString(), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.successfulRows").value(2))
                .andExpect(jsonPath("$.failedRows").value(0))
                .andExpect(jsonPath("$.importedQuantity").value(80))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertThat(balance(productId)).isEqualByComparingTo("50");
        assertThat(balance(product2)).isEqualByComparingTo("30");
    }

    @Test
    void acceptsTrailingZeroDecimalsButRejectsRealFractionsForAWholeUnit() throws Exception {
        // PCS has scale 0. "7.00" is a whole number with trailing zeros and must be accepted (like the inbound API);
        // "7.5" is a genuine fraction and must be rejected.
        String csv = HEADER + "\n"
                + row(warehouseCode, sku, "7.00", "", "", "", "R")
                + row(warehouseCode, sku, "7.5", "", "", "", "R");

        upload(csv, UUID.randomUUID().toString(), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.successfulRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(1))
                .andExpect(jsonPath("$.errors[0].errorCode").value("INVALID_QUANTITY_SCALE"))
                .andExpect(jsonPath("$.errors[0].rowNumber").value(2));

        assertThat(balance(productId)).isEqualByComparingTo("7");
    }

    @Test
    void rejectsAFileWithTheWrongHeader() throws Exception {
        upload("wrong,header\n1,2", UUID.randomUUID().toString(), token)
                .andExpect(problem(400, "CSV_IMPORT_FAILED"));
    }

    @Test
    void reportsInvalidRowsAndImportsTheRest() throws Exception {
        String csv = HEADER + "\n"
                + row(warehouseCode, sku, "10", "", "", "", "REF")
                + row(warehouseCode, "NO-SUCH-SKU", "5", "", "", "", "REF")
                + row(warehouseCode, sku, "-3", "", "", "", "REF")
                + row("NO-WH", sku, "4", "", "", "", "REF");

        upload(csv, UUID.randomUUID().toString(), token)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalRows").value(4))
                .andExpect(jsonPath("$.successfulRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(3))
                .andExpect(jsonPath("$.errors[*].errorCode").value(
                        containsInAnyOrder("MISSING_PRODUCT", "INVALID_QUANTITY", "MISSING_WAREHOUSE")))
                .andExpect(jsonPath("$.errors[?(@.errorCode == 'MISSING_PRODUCT')].rowNumber").value(2));

        assertThat(balance(productId)).isEqualByComparingTo("10");
    }

    @Test
    void validatesBatchAndExpirationRules() throws Exception {
        long lot = api.batchProduct(unique("lot").toUpperCase(), category, pieces);
        String future = LocalDate.now().plusMonths(6).toString();
        String csv = HEADER + "\n"
                + row(warehouseCode, skuOf(lot), "5", "", "", future, "B")              // batch required
                + row(warehouseCode, skuOf(lot), "5", "", "L1", "not-a-date", "B")       // bad date
                + row(warehouseCode, skuOf(lot), "5", "", "L2", "2000-01-01", "B")       // expired
                + row(warehouseCode, sku, "5", "", "L3", "", "B")                        // batch not allowed
                + row(warehouseCode, skuOf(lot), "7", "", "L4", future, "B");            // valid

        upload(csv, UUID.randomUUID().toString(), token)
                .andExpect(jsonPath("$.successfulRows").value(1))
                .andExpect(jsonPath("$.failedRows").value(4))
                .andExpect(jsonPath("$.errors[*].errorCode").value(containsInAnyOrder(
                        "BATCH_REQUIRED", "INVALID_EXPIRATION_DATE", "BATCH_EXPIRED", "BATCH_NOT_ALLOWED")));

        assertThat(balance(lot)).isEqualByComparingTo("7");
    }

    @Test
    void repeatingAnUploadWithTheSameKeyReturnsTheOriginalResult() throws Exception {
        String key = UUID.randomUUID().toString();
        String csv = HEADER + "\n" + row(warehouseCode, sku, "9", "", "", "", "REF");

        upload(csv, key, token).andExpect(status().isCreated()).andExpect(jsonPath("$.successfulRows").value(1));
        upload(csv, key, token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.successfulRows").value(1));

        assertThat(balance(productId)).isEqualByComparingTo("9");
    }

    @Test
    void requiresAnIdempotencyKey() throws Exception {
        upload(HEADER + "\n" + row(warehouseCode, sku, "1", "", "", "", "R"), null, token)
                .andExpect(problem(400, "IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void ordinaryUsersCannotImport() throws Exception {
        String username = unique("imp-user");
        registerUser(username);
        String userToken = tokenFor(username, USER_PASSWORD);

        upload(HEADER + "\n" + row(warehouseCode, sku, "1", "", "", "", "R"), UUID.randomUUID().toString(), userToken)
                .andExpect(problem(403, "ACCESS_DENIED"));
    }

    private String skuOf(long productId) {
        return jdbc.queryForObject("SELECT sku FROM product WHERE id = ?", String.class, productId);
    }

    private ResultActions upload(String csv, String key, String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "import.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/v1/imports/stock-inbound").file(file)
                .header(HttpHeaders.AUTHORIZATION, bearer(token));
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private static String row(String warehouseCode, String sku, String qty, String cost, String batch,
                              String expiration, String reference) {
        return "%s,%s,%s,%s,%s,%s,%s\n".formatted(warehouseCode, sku, qty, cost, batch, expiration, reference);
    }

    private BigDecimal balance(long product) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(quantity), 0) FROM inventory_balance WHERE warehouse_id = ? AND product_id = ?""",
                BigDecimal.class, warehouseId, product);
    }
}
