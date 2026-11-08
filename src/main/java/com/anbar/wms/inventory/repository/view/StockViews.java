package com.anbar.wms.inventory.repository.view;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class StockViews {

    private StockViews() {
    }

    public record StockLine(Long productId, String sku, String productName, String unitCode, Long batchId,
                            String batchNumber, LocalDate expiresOn, BigDecimal quantity, Instant updatedAt) {
    }

    public record ProductQuantity(Long productId, String sku, String productName, String unitCode,
                                  BigDecimal quantity, BigDecimal minStockQuantity) {
    }

    public record WarehouseQuantity(Long warehouseId, String warehouseCode, String warehouseName, BigDecimal quantity) {
    }

    public record BatchQuantity(Long batchId, String batchNumber, LocalDate expiresOn, BigDecimal quantity) {
    }

    public record LowStock(Long productId, String sku, String productName, String unitCode, BigDecimal quantity,
                           BigDecimal minStockQuantity, BigDecimal shortage) {
    }

    public record WarehouseProductMatch(long productId, String sku, String productName, String barcode,
                                        String categoryName, String unitCode, BigDecimal availableQuantity) {
    }
}
