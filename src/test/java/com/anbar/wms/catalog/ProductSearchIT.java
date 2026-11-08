package com.anbar.wms.catalog;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class ProductSearchIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;
    private long category;
    private long pieces;
    private String sku;
    private String barcode;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        category = api.category(unique("Search"));
        sku = unique("srch").toUpperCase();
        barcode = unique("bar").replace("-", "");
        api.post("/api/v1/products",
                ApiFixtures.productJson(sku, "Searchable widget", category, pieces, "0", barcode, false))
                .andExpect(status().isCreated());
    }

    @Test
    void findsByExactSkuCaseInsensitively() throws Exception {
        api.get("/api/v1/products/search?sku=" + sku.toLowerCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].sku").value(sku))
                .andExpect(jsonPath("$.content[0].barcode").value(barcode))
                .andExpect(jsonPath("$.content[0].categoryName").isString());
    }

    @Test
    void findsByBarcodeAndByNamePrefix() throws Exception {
        api.get("/api/v1/products/search?barcode=" + barcode)
                .andExpect(jsonPath("$.content[0].sku").value(sku));
        api.get("/api/v1/products/search?q=Searchable&categoryId=" + category)
                .andExpect(jsonPath("$.content[*].sku").value(hasItem(sku)));
    }

    @Test
    void exactSkuAndBarcodeLookups() throws Exception {
        api.get("/api/v1/products/sku/" + sku.toLowerCase())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value(sku));
        api.get("/api/v1/products/barcode/" + barcode)
                .andExpect(jsonPath("$.sku").value(sku));
        api.get("/api/v1/products/sku/NO-SUCH-SKU").andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        api.get("/api/v1/products/barcode/nosuchbarcode").andExpect(problem(404, "RESOURCE_NOT_FOUND"));
    }

    @Test
    void emptyResultIsAnEmptyPageNotAnError() throws Exception {
        api.get("/api/v1/products/search?sku=DEFINITELY-MISSING")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void rejectsUnknownSortProperty() throws Exception {
        api.get("/api/v1/products/search?sort=secret").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    @Test
    void warehouseScopedSearchReportsAvailableQuantity() throws Exception {
        long warehouse = api.warehouse(unique("WS").replace("-", ""));
        long productId = ApiFixtures.id(api.post("/api/v1/products",
                ApiFixtures.productJson(unique("wp").toUpperCase(), "Warehouse widget", category, pieces, "0", null, false)));
        api.post("/api/v1/stock-movements/inbound", """
                {"productId": %d, "warehouseId": %d, "quantity": 15}""".formatted(productId, warehouse))
                .andExpect(status().isCreated());

        api.get("/api/v1/warehouses/%d/products/search?q=Warehouse&inStockOnly=true".formatted(warehouse))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value((int) productId))
                .andExpect(jsonPath("$.content[0].availableQuantity").value(15));
        api.get("/api/v1/warehouses/%d/products/search?sku=%s&inStockOnly=true"
                .formatted(warehouse, sku))
                .andExpect(jsonPath("$.totalElements").value(0));
    }
}
