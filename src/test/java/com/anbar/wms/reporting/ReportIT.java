package com.anbar.wms.reporting;

import static com.anbar.wms.support.ProblemMatchers.problem;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.ApiFixtures;
import java.time.LocalDate;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Reports are read through a warehouse manager scoped to a single fresh warehouse, so the shared, persistent test
 * database can't leak other runs' data into the ranked reports.
 */
class ReportIT extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private ApiFixtures api;
    private ApiFixtures managerApi;
    private long category;
    private long pieces;
    private long warehouse;
    private long product;

    @BeforeEach
    void setUp() throws Exception {
        api = new ApiFixtures(mockMvc, adminToken());
        pieces = jdbc.queryForObject("SELECT id FROM unit_of_measure WHERE code = 'PCS'", Long.class);
        category = api.category(unique("Rep"));
        warehouse = api.warehouse(unique("RP").replace("-", "").toUpperCase());
        product = api.product(unique("rep").toUpperCase(), "Reported widget", category, pieces, "0");
        api.inbound(product, warehouse, "100").andExpect(status().isCreated());
        api.outbound(product, warehouse, "30").andExpect(status().isCreated());

        String managerName = unique("rep-mgr");
        long managerId = registerUser(managerName);
        api.makeWarehouseManager(managerId);
        api.assignManager(warehouse, managerId);
        managerApi = new ApiFixtures(mockMvc, tokenFor(managerName, USER_PASSWORD));
    }

    @Test
    void inboundOutboundStatisticsAreCorrectForTheProduct() throws Exception {
        managerApi.get("/api/v1/reports/inbound-outbound?productId=" + product)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalInbound").value(100))
                .andExpect(jsonPath("$.totalOutbound").value(30))
                .andExpect(jsonPath("$.netMovement").value(70))
                .andExpect(jsonPath("$.movementCount").value(2))
                .andExpect(jsonPath("$.breakdown").isNotEmpty());
    }

    @Test
    void topProductsShowsInboundAndOutboundVolume() throws Exception {
        managerApi.get("/api/v1/reports/top-products?from=" + LocalDate.now().minusDays(1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].productId").value((int) product))
                .andExpect(jsonPath("$.content[0].inboundQuantity").value(100))
                .andExpect(jsonPath("$.content[0].outboundQuantity").value(30))
                .andExpect(jsonPath("$.content[0].totalVolume").value(130));
    }

    @Test
    void inventoryValuationUsesPurchaseCost() throws Exception {
        // 70 on hand * purchase price 1.50
        managerApi.get("/api/v1/reports/inventory-valuation?groupBy=PRODUCT&warehouseId=" + warehouse)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].key").value(String.valueOf(product)))
                .andExpect(jsonPath("$.content[0].totalQuantity").value(70))
                .andExpect(jsonPath("$.content[0].totalValue").value(105.0));
        managerApi.get("/api/v1/reports/inventory-valuation?groupBy=TOTAL&warehouseId=" + warehouse)
                .andExpect(jsonPath("$.content[0].totalValue").value(105.0));
    }

    @Test
    void warehousePerformanceAggregatesStockAndActivity() throws Exception {
        managerApi.get("/api/v1/reports/warehouse-performance")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].warehouseId").value((int) warehouse))
                .andExpect(jsonPath("$.content[0].inventoryValue").value(105.0))
                .andExpect(jsonPath("$.content[0].inboundQuantity").value(100))
                .andExpect(jsonPath("$.content[0].outboundQuantity").value(30))
                .andExpect(jsonPath("$.content[0].totalProducts").value(1));
    }

    @Test
    void supplierPerformanceCountsOrdersAndValue() throws Exception {
        long supplier = api.supplier(unique("Sup"),
                "%010d".formatted(ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_999_999_999L)));
        api.post("/api/v1/purchase-orders", """
                {"supplierId": %d, "warehouseId": %d, "items": [{"productId": %d, "orderedQuantity": 10, "unitPrice": 5}]}"""
                .formatted(supplier, warehouse, product)).andExpect(status().isCreated());

        managerApi.get("/api/v1/reports/supplier-performance")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].supplierId").value((int) supplier))
                .andExpect(jsonPath("$.content[0].totalPurchaseValue").value(50.0))
                .andExpect(jsonPath("$.content[0].totalPurchasedQuantity").value(10))
                .andExpect(jsonPath("$.content[0].outstandingOrders").value(0));
    }

    @Test
    void stockMovementReportFiltersByProductAndUser() throws Exception {
        managerApi.get("/api/v1/reports/stock-movements?productId=" + product)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].movementType").value(hasItem("INBOUND")))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void reportsRejectAnUnknownSortProperty() throws Exception {
        managerApi.get("/api/v1/reports/top-products?sort=secret").andExpect(problem(400, "INVALID_SORT_PROPERTY"));
    }

    @Test
    void ordinaryUsersCannotSeeReports() throws Exception {
        String username = unique("rep-user");
        registerUser(username);
        String userToken = tokenFor(username, USER_PASSWORD);

        mockMvc.perform(get("/api/v1/reports/warehouse-performance").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(problem(403, "ACCESS_DENIED"));
    }
}
