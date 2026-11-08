package com.anbar.wms.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class StockResponses {

    private StockResponses() {
    }

    public record StockLineResponse(long productId, String sku, String productName, String unitCode, Long batchId,
                                    String batchNumber, LocalDate expiresOn, BigDecimal quantity, Instant updatedAt) {
    }

    public record WarehouseProductResponse(long productId, String sku, String productName, String unitCode,
                                           BigDecimal quantity, BigDecimal minStockQuantity, boolean belowMinimum) {
    }

    public record ProductWarehouseStockResponse(long warehouseId, String warehouseCode, long productId, String sku,
                                                String productName, String unitCode, BigDecimal quantity,
                                                List<BatchQuantityResponse> batches) {
    }

    public record ProductStockResponse(long productId, String sku, String productName, String unitCode,
                                       BigDecimal totalQuantity, BigDecimal minStockQuantity, boolean belowMinimum,
                                       List<WarehouseQuantityResponse> warehouses) {
    }

    public record LowStockResponse(long productId, String sku, String productName, String unitCode,
                                   BigDecimal quantity, BigDecimal minStockQuantity, BigDecimal shortage) {
    }

    public record WarehouseQuantityResponse(long warehouseId, String warehouseCode, String warehouseName,
                                            BigDecimal quantity) {
    }

    public record BatchQuantityResponse(Long batchId, String batchNumber, LocalDate expiresOn, BigDecimal quantity) {
    }

    public record WarehouseProductSearchResponse(long productId, String sku, String productName, String barcode,
                                                 String categoryName, String unitCode, BigDecimal availableQuantity) {
    }
}
