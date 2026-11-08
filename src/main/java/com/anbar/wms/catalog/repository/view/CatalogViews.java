package com.anbar.wms.catalog.repository.view;

/** Lightweight projections returned by catalog repositories, kept out of the DTO layer. */
public final class CatalogViews {

    private CatalogViews() {
    }

    public record ProductSummary(long id, String sku, String name, String barcode, long categoryId,
                                 String categoryName, String unitCode, boolean active) {
    }
}
