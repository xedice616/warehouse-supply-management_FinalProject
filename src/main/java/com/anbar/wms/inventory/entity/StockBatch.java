package com.anbar.wms.inventory.entity;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.AuditableEntity;
import com.anbar.wms.supplier.entity.Supplier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A lot of one product, identified by its batch number, with optional manufacture and expiry dates.
 * The same batch can sit in several warehouses; its quantity per warehouse is in {@link InventoryBalance}.
 * Expiry is a calendar date in the business time zone.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockBatch extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(name = "batch_number", nullable = false, length = 64)
    private String batchNumber;

    @Column(name = "manufactured_on")
    private LocalDate manufacturedOn;

    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Who supplied this lot, when it came from a purchase. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    /** The purchase order item this lot was first received for; a plain id to keep inventory independent of purchasing. */
    @Column(name = "purchase_order_line_id")
    private Long purchaseOrderLineId;

    public StockBatch(Product product, String batchNumber, LocalDate manufacturedOn, LocalDate expiresOn,
                      Supplier supplier) {
        this(product, batchNumber, manufacturedOn, expiresOn, supplier, null, null);
    }

    public StockBatch(Product product, String batchNumber, LocalDate manufacturedOn, LocalDate expiresOn,
                      Supplier supplier, BigDecimal unitCost, Long purchaseOrderLineId) {
        if (manufacturedOn != null && expiresOn != null && !expiresOn.isAfter(manufacturedOn)) {
            throw new IllegalArgumentException("Expiry date must be after the manufacturing date");
        }
        this.product = Objects.requireNonNull(product, "product");
        this.batchNumber = Objects.requireNonNull(batchNumber, "batchNumber").trim();
        this.manufacturedOn = manufacturedOn;
        this.expiresOn = expiresOn;
        this.supplier = supplier;
        this.unitCost = unitCost;
        this.purchaseOrderLineId = purchaseOrderLineId;
    }

    public boolean isExpiredOn(LocalDate day) {
        return expiresOn != null && expiresOn.isBefore(day);
    }
}
