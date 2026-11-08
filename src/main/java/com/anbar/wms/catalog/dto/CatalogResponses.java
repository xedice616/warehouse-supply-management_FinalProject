package com.anbar.wms.catalog.dto;

import java.math.BigDecimal;
import java.time.Instant;

public final class CatalogResponses {

    private CatalogResponses() {
    }

    public record UnitResponse(
            long id, String code, String name, int decimalScale, boolean active,
            long version, Instant createdAt, Instant updatedAt) {
    }

    public record CategoryResponse(
            long id, String name, Long parentId, boolean active,
            long version, Instant createdAt, Instant updatedAt) {
    }

    public record ProductResponse(
            long id,
            String sku,
            String name,
            String description,
            String barcode,
            CategoryRef category,
            UnitRef unit,
            boolean batchTracked,
            boolean expiryTracked,
            BigDecimal minStockQuantity,
            BigDecimal purchasePrice,
            BigDecimal sellingPrice,
            boolean active,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record CategoryRef(long id, String name) {
    }

    public record UnitRef(long id, String code, String name, int decimalScale) {
    }
}
