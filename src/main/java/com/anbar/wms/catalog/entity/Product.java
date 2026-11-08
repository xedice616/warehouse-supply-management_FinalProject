package com.anbar.wms.catalog.entity;

import com.anbar.wms.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A stock-keeping item. Deliberately has no quantity: stock is held per warehouse in the inventory module and
 * changes only through stock movements. The SKU is the permanent business identifier and never changes.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends AuditableEntity {

    @Column(name = "sku", nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "barcode", length = 64)
    private String barcode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private ProductCategory category;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uom_id", nullable = false)
    private UnitOfMeasure unitOfMeasure;

    @Column(name = "batch_tracked", nullable = false)
    private boolean batchTracked;

    @Column(name = "expiry_tracked", nullable = false)
    private boolean expiryTracked;

    @Column(name = "min_stock_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal minStockQuantity;

    @Column(name = "purchase_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal purchasePrice;

    @Column(name = "selling_price", precision = 19, scale = 4)
    private BigDecimal sellingPrice;

    @Column(name = "active", nullable = false)
    private boolean active;

    public Product(String sku, ProductDetails details) {
        this.sku = normalizeSku(Objects.requireNonNull(sku, "sku"));
        this.active = true;
        update(details);
    }

    public static String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
    }

    public void update(ProductDetails details) {
        if (details.expiryTracked() && !details.batchTracked()) {
            throw new IllegalArgumentException("An expiry-tracked product must also be batch-tracked");
        }
        this.name = details.name().trim();
        this.description = details.description();
        this.barcode = details.barcode();
        this.category = Objects.requireNonNull(details.category(), "category");
        this.unitOfMeasure = Objects.requireNonNull(details.unitOfMeasure(), "unitOfMeasure");
        this.batchTracked = details.batchTracked();
        this.expiryTracked = details.expiryTracked();
        this.minStockQuantity = Objects.requireNonNull(details.minStockQuantity(), "minStockQuantity");
        this.purchasePrice = Objects.requireNonNull(details.purchasePrice(), "purchasePrice");
        this.sellingPrice = details.sellingPrice();
    }

    /** Changes that would reinterpret existing stock history: a different unit or batch tracking. */
    public boolean changesStockSemantics(ProductDetails details) {
        return !unitOfMeasure.equals(details.unitOfMeasure())
                || batchTracked != details.batchTracked()
                || expiryTracked != details.expiryTracked();
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }
}
