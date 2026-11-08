package com.anbar.wms.catalog.dto;

public final class ProductSearchResponses {

    private ProductSearchResponses() {
    }

    /** Lightweight product row for search results: no lazy relationships are loaded, only these columns. */
    public record ProductSummary(
            long id,
            String sku,
            String name,
            String barcode,
            long categoryId,
            String categoryName,
            String unitCode,
            boolean active) {
    }
}
