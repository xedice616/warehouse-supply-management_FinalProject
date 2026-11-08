package com.anbar.wms.inventory.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public final class BatchResponses {

    private BatchResponses() {
    }

    /**
     * A batch in one warehouse. {@code availableQuantity} is the current stock of the batch there;
     * {@code initialQuantity} is everything that ever entered that warehouse for it (receipts, transfers in,
     * positive adjustments), so available never exceeds initial. Both come from the stock ledger's projection.
     *
     * @param daysUntilExpiration negative once expired; null for batches without an expiration date
     */
    public record BatchStockResponse(
            long batchId,
            String batchNumber,
            ProductRef product,
            WarehouseRef warehouse,
            BigDecimal initialQuantity,
            BigDecimal availableQuantity,
            LocalDate productionDate,
            LocalDate expirationDate,
            Long daysUntilExpiration,
            boolean expired,
            Long purchaseOrderItemId,
            BigDecimal unitCost,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** A product's expiration exposure: available stock already expired, and expiring within the horizon. */
    public record ExpirationRiskResponse(
            ProductRef product,
            int batchCount,
            BigDecimal expiredQuantity,
            BigDecimal expiringQuantity,
            LocalDate earliestExpiration) {
    }

    public record ProductRef(long id, String sku, String name, String unitCode) {
    }

    public record WarehouseRef(long id, String code, String name) {
    }
}
