package com.anbar.wms.catalog;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static com.anbar.wms.support.ApiFixtures.productJson;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import com.anbar.wms.support.StockLedgerFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

class CatalogIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;
    private long pieces;
    private long kilograms;
    private long category;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        kilograms = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'KG'", Long.class);
        category = api.category(unique("Category"));
    }

    @Nested
    class Products {

        @Test
        void createReturns201WithLocationAndNormalizedSku() throws Exception {
            String sku = unique("bolt");

            api.post("/api/v1/products", productJson(sku, "Hex bolt", category, pieces, "100", null, false))
                    .andExpect(status().isCreated())
                    .andExpect(header().string(HttpHeaders.LOCATION, matchesPattern(".*/api/v1/products/\\d+")))
                    .andExpect(jsonPath("$.sku").value(sku.toUpperCase()))
                    .andExpect(jsonPath("$.unit.code").value("PCS"))
                    .andExpect(jsonPath("$.category.id").value(category))
                    .andExpect(jsonPath("$.minStockQuantity").value(100))
                    .andExpect(jsonPath("$.purchasePrice").value(1.5))
                    .andExpect(jsonPath("$.sellingPrice").value(2.75))
                    .andExpect(jsonPath("$.active").value(true))
                    .andExpect(jsonPath("$.version").value(0))
                    .andExpect(jsonPath("$.createdAt").isString())
                    .andExpect(jsonPath("$.quantity").doesNotExist());
        }

        @Test
        void duplicateSkuIgnoringCaseIsConflict() throws Exception {
            String sku = unique("dup");
            api.product(sku, "First", category, pieces, "0");

            api.post("/api/v1/products", productJson(sku.toUpperCase(), "Second", category, pieces, "0", null, false))
                    .andExpect(problem(409, "DUPLICATE_RESOURCE"))
                    .andExpect(jsonPath("$.field").value("sku"));
        }

        @Test
        void duplicateBarcodeIsConflict() throws Exception {
            String barcode = unique("4006381");
            api.post("/api/v1/products", productJson(unique("a"), "A", category, pieces, "0", barcode, false))
                    .andExpect(status().isCreated());

            api.post("/api/v1/products", productJson(unique("b"), "B", category, pieces, "0", barcode, false))
                    .andExpect(problem(409, "DUPLICATE_RESOURCE"))
                    .andExpect(jsonPath("$.field").value("barcode"));
        }

        @Test
        void invalidDataListsEveryField() throws Exception {
            api.post("/api/v1/products", """
                    {"sku": "bad sku!", "name": " ", "unitId": %d, "minStockQuantity": 1.123456,
                     "purchasePrice": -1, "sellingPrice": -0.01}""".formatted(pieces))
                    .andExpect(problem(400, "VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[*].field",
                            hasItems("sku", "name", "categoryId", "minStockQuantity", "purchasePrice", "sellingPrice")));
        }

        @Test
        void expiryTrackingRequiresBatchTracking() throws Exception {
            api.post("/api/v1/products", """
                    {"sku": "%s", "name": "Milk", "categoryId": %d, "unitId": %d, "minStockQuantity": 0,
                     "purchasePrice": 1, "batchTracked": false, "expiryTracked": true}"""
                    .formatted(unique("milk"), category, pieces))
                    .andExpect(problem(400, "VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.errors[0].field").value("trackingConsistent"));
        }

        @Test
        void minimumMustFitTheUnit() throws Exception {
            api.post("/api/v1/products", productJson(unique("p"), "Pieces", category, pieces, "2.5", null, false))
                    .andExpect(problem(400, "INVALID_QUANTITY_SCALE"));
            api.post("/api/v1/products", productJson(unique("k"), "Kilos", category, kilograms, "2.5", null, false))
                    .andExpect(status().isCreated());
        }

        @Test
        void stockCannotBeSetThroughTheProductApi() throws Exception {
            api.post("/api/v1/products", """
                    {"sku": "%s", "name": "X", "categoryId": %d, "unitId": %d, "minStockQuantity": 0,
                     "purchasePrice": 1, "quantity": 500}""".formatted(unique("x"), category, pieces))
                    .andExpect(problem(400, "MALFORMED_REQUEST"))
                    .andExpect(jsonPath("$.detail").value("Unknown property 'quantity'"));
        }

        @Test
        void updateBumpsVersionAndRejectsStaleWrites() throws Exception {
            long id = api.product(unique("upd"), "Before", category, pieces, "5");
            String update = """
                    {"name": "After", "categoryId": %d, "unitId": %d, "minStockQuantity": 10,
                     "purchasePrice": 2, "sellingPrice": null, "version": 0}""".formatted(category, pieces);

            api.put("/api/v1/products/" + id, update)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("After"))
                    .andExpect(jsonPath("$.minStockQuantity").value(10))
                    .andExpect(jsonPath("$.sellingPrice").doesNotExist())
                    .andExpect(jsonPath("$.version").value(1));
            api.put("/api/v1/products/" + id, update).andExpect(problem(409, "CONCURRENT_MODIFICATION"));
        }

        @Test
        void deactivationHidesFromActiveListAndCanBeUndone() throws Exception {
            long id = api.product(unique("off"), "Off", category, pieces, "0");

            api.patch("/api/v1/products/" + id + "/status", "{\"active\": false}")
                    .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
            api.get("/api/v1/products?categoryId=" + category + "&active=true")
                    .andExpect(jsonPath("$.totalElements").value(0));
            api.patch("/api/v1/products/" + id + "/status", "{\"active\": true}")
                    .andExpect(jsonPath("$.active").value(true));
        }

        @Test
        void inactiveCategoryCannotTakeNewProducts() throws Exception {
            api.patch("/api/v1/categories/" + category + "/status", "{\"active\": false}").andExpect(status().isOk());

            api.post("/api/v1/products", productJson(unique("late"), "Late", category, pieces, "0", null, false))
                    .andExpect(problem(422, "INACTIVE_RESOURCE"));
        }

        @Test
        void unusedProductIsDeletedPhysically() throws Exception {
            long id = api.product(unique("tmp"), "Temporary", category, pieces, "0");

            api.delete("/api/v1/products/" + id).andExpect(status().isNoContent());
            api.get("/api/v1/products/" + id).andExpect(problem(404, "RESOURCE_NOT_FOUND"));
        }

        @Test
        void productWithStockHistoryIsNeverDeletedNorDeactivatedWhileStocked() throws Exception {
            long id = api.product(unique("hist"), "Has history", category, pieces, "0");
            long warehouse = api.warehouse(unique("W").substring(0, 12));
            new StockLedgerFixture(jdbc).receive(warehouse, id, null, "3");

            api.delete("/api/v1/products/" + id).andExpect(problem(409, "RESOURCE_IN_USE"));
            api.patch("/api/v1/products/" + id + "/status", "{\"active\": false}").andExpect(problem(409, "STOCK_ON_HAND"));
            api.put("/api/v1/products/" + id, """
                    {"name": "Has history", "categoryId": %d, "unitId": %d, "minStockQuantity": 0,
                     "purchasePrice": 1, "version": 0}""".formatted(category, kilograms))
                    .andExpect(problem(409, "STOCK_HISTORY_EXISTS"));
        }

        @Test
        void listSupportsPagingSortingAndSearch() throws Exception {
            String prefix = unique("srt");
            for (String name : new String[] {"Charlie", "Alpha", "Bravo"}) {
                api.product(prefix + "-" + name, name, category, pieces, "0");
            }

            api.get("/api/v1/products?categoryId=%d&size=2&sort=name,desc".formatted(category))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[*].name", contains("Charlie", "Bravo")))
                    .andExpect(jsonPath("$.totalElements").value(3))
                    .andExpect(jsonPath("$.totalPages").value(2))
                    .andExpect(jsonPath("$.first").value(true));
            api.get("/api/v1/products?categoryId=%d&size=2&page=1&sort=name,desc".formatted(category))
                    .andExpect(jsonPath("$.content[*].name", contains("Alpha")))
                    .andExpect(jsonPath("$.last").value(true));
            api.get("/api/v1/products?q=" + prefix.toLowerCase())
                    .andExpect(jsonPath("$.totalElements").value(3));
            api.get("/api/v1/products?sort=purchasePriceX").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
        }
    }

    @Nested
    class Access {

        @Test
        void anyAuthenticatedUserReadsButOnlyAdminWrites() throws Exception {
            String username = unique("reader");
            registerUser(username);
            String token = tokenFor(username, USER_PASSWORD);

            mockMvc.perform(get("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/v1/products").header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(productJson(unique("no"), "No", category, pieces, "0", null, false)))
                    .andExpect(problem(403, "ACCESS_DENIED"));
            mockMvc.perform(post("/api/v1/categories").header(HttpHeaders.AUTHORIZATION, bearer(token))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                    .andExpect(problem(403, "ACCESS_DENIED"));
            mockMvc.perform(get("/api/v1/products")).andExpect(problem(401, "AUTHENTICATION_REQUIRED"));
        }
    }

    @Nested
    class Categories {

        @Test
        void siblingNamesAreUniqueIgnoringCase() throws Exception {
            String name = unique("Hand tools");
            api.post("/api/v1/categories", "{\"name\": \"%s\", \"parentId\": %d}".formatted(name, category))
                    .andExpect(status().isCreated());

            api.post("/api/v1/categories", "{\"name\": \"%s\", \"parentId\": %d}".formatted(name.toUpperCase(), category))
                    .andExpect(problem(409, "DUPLICATE_RESOURCE"))
                    .andExpect(jsonPath("$.field").value("name"));
        }

        @Test
        void categoryCannotMoveUnderItsOwnDescendant() throws Exception {
            long child = ApiFixtures.id(api.post("/api/v1/categories",
                    "{\"name\": \"%s\", \"parentId\": %d}".formatted(unique("child"), category)));

            api.put("/api/v1/categories/" + category, """
                    {"name": "Root", "parentId": %d, "version": 0}""".formatted(child))
                    .andExpect(problem(422, "INVALID_CATEGORY_HIERARCHY"));
        }

        @Test
        void categoryInUseCannotBeDeleted() throws Exception {
            api.product(unique("c"), "In category", category, pieces, "0");

            api.delete("/api/v1/categories/" + category).andExpect(problem(409, "RESOURCE_IN_USE"));
        }
    }

    @Nested
    class Units {

        @Test
        void unitCodesAreUniqueAndScaleIsFixedOnceUsed() throws Exception {
            String code = unique("U").replace("-", "").substring(0, 10);
            long unit = ApiFixtures.id(api.post("/api/v1/units",
                    "{\"code\": \"%s\", \"name\": \"Test unit\", \"decimalScale\": 2}".formatted(code))
                    .andExpect(status().isCreated()));
            api.post("/api/v1/units", "{\"code\": \"%s\", \"name\": \"Again\", \"decimalScale\": 0}".formatted(code.toLowerCase()))
                    .andExpect(problem(409, "DUPLICATE_RESOURCE"));

            api.product(unique("u"), "Uses unit", category, unit, "1.25");

            api.put("/api/v1/units/" + unit, "{\"name\": \"Renamed\", \"decimalScale\": 0, \"version\": 0}")
                    .andExpect(problem(409, "RESOURCE_IN_USE"));
            api.put("/api/v1/units/" + unit, "{\"name\": \"Renamed\", \"decimalScale\": 2, \"version\": 0}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name").value("Renamed"));
        }
    }
}
