package com.anbar.wms.catalog.entity;

import java.math.BigDecimal;

public record ProductDetails(
        String name,
        String description,
        String barcode,
        ProductCategory category,
        UnitOfMeasure unitOfMeasure,
        boolean batchTracked,
        boolean expiryTracked,
        BigDecimal minStockQuantity,
        BigDecimal purchasePrice,
        BigDecimal sellingPrice) {
}
