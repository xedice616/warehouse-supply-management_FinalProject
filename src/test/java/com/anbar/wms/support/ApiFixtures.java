package com.anbar.wms.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** Creates master data through the real API as an administrator. */
public final class ApiFixtures {

    private final MockMvc mockMvc;
    private final String adminToken;

    public ApiFixtures(MockMvc mockMvc, String adminToken) {
        this.mockMvc = mockMvc;
        this.adminToken = adminToken;
    }

    public ResultActions post(String path, String json) throws Exception {
        return send(MockMvcRequestBuilders.post(path), json);
    }

    public ResultActions put(String path, String json) throws Exception {
        return send(MockMvcRequestBuilders.put(path), json);
    }

    public ResultActions patch(String path, String json) throws Exception {
        return send(MockMvcRequestBuilders.patch(path), json);
    }

    public ResultActions delete(String path) throws Exception {
        return send(MockMvcRequestBuilders.delete(path), null);
    }

    public ResultActions get(String path) throws Exception {
        return send(MockMvcRequestBuilders.get(path), null);
    }

    public long category(String name) throws Exception {
        return id(post("/api/v1/categories", "{\"name\": \"%s\"}".formatted(name)).andExpect(status().isCreated()));
    }

    public long product(String sku, String name, long categoryId, long unitId, String minStock) throws Exception {
        return id(post("/api/v1/products", productJson(sku, name, categoryId, unitId, minStock, null, false))
                .andExpect(status().isCreated()));
    }

    public long batchProduct(String sku, long categoryId, long unitId) throws Exception {
        return id(post("/api/v1/products", productJson(sku, "Batch " + sku, categoryId, unitId, "0", null, true))
                .andExpect(status().isCreated()));
    }

    public long warehouse(String code) throws Exception {
        return id(post("/api/v1/warehouses", """
                {"code": "%s", "name": "Warehouse %s", "address": {"city": "Baku", "countryCode": "az"}}"""
                .formatted(code, code)).andExpect(status().isCreated()));
    }

    public void setWarehouseActive(long id, boolean active) throws Exception {
        patch("/api/v1/warehouses/" + id + "/status", "{\"active\": %s}".formatted(active)).andExpect(status().isOk());
    }

    public void makeWarehouseManager(long userId) throws Exception {
        put("/api/v1/users/" + userId + "/roles", "{\"roles\": [\"WAREHOUSE_MANAGER\"]}").andExpect(status().isOk());
    }

    public ResultActions assignManager(long warehouseId, long userId) throws Exception {
        return put("/api/v1/warehouses/" + warehouseId + "/managers/" + userId, null);
    }

    public ResultActions inbound(long productId, long warehouseId, String quantity) throws Exception {
        return post("/api/v1/stock-movements/inbound", """
                {"productId": %d, "warehouseId": %d, "quantity": %s}""".formatted(productId, warehouseId, quantity));
    }

    public ResultActions outbound(long productId, long warehouseId, String quantity) throws Exception {
        return post("/api/v1/stock-movements/outbound", """
                {"productId": %d, "warehouseId": %d, "quantity": %s}""".formatted(productId, warehouseId, quantity));
    }

    public ResultActions transfer(long productId, long from, long to, String quantity) throws Exception {
        return post("/api/v1/stock-movements/transfer", """
                {"productId": %d, "sourceWarehouseId": %d, "destinationWarehouseId": %d, "quantity": %s}"""
                .formatted(productId, from, to, quantity));
    }

    public long supplier(String companyName, String taxId) throws Exception {
        return id(post("/api/v1/suppliers", """
                {"companyName": "%s", "taxIdentificationNumber": "%s"}""".formatted(companyName, taxId))
                .andExpect(status().isCreated()));
    }

    public static String productJson(String sku, String name, long categoryId, long unitId, String minStock,
                                     String barcode, boolean batchTracked) {
        return """
                {"sku": "%s", "name": "%s", "categoryId": %d, "unitId": %d, "minStockQuantity": %s,
                 "purchasePrice": 1.50, "sellingPrice": 2.75, "batchTracked": %s, "expiryTracked": %s%s}"""
                .formatted(sku, name, categoryId, unitId, minStock, batchTracked, batchTracked,
                        barcode == null ? "" : ", \"barcode\": \"%s\"".formatted(barcode));
    }

    public static long id(ResultActions result) throws Exception {
        return ((Number) JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private ResultActions send(MockHttpServletRequestBuilder request, String json) throws Exception {
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken);
        if (json != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        return mockMvc.perform(request);
    }
}
