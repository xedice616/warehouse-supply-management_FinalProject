package com.anbar.wms.audit.entity;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockBatch;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/**
 * One product (and batch) on an audit. Recording a count also snapshots the system balance at that instant as the
 * expected quantity; the discrepancy (counted − expected) is computed by the database. Movements posted after the
 * count change the balance and the shelf alike, so applying the discrepancy to the balance at completion is correct.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryAuditItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "inventory_audit_id", nullable = false)
    private InventoryAudit inventoryAudit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private StockBatch batch;

    @Column(name = "expected_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedQuantity;

    @Column(name = "counted_quantity", precision = 19, scale = 4)
    private BigDecimal countedQuantity;

    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "discrepancy", precision = 19, scale = 4, insertable = false, updatable = false)
    private BigDecimal discrepancy;

    @Column(name = "notes", length = 500)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "counted_by")
    private AppUser countedBy;

    @Column(name = "counted_at")
    private Instant countedAt;

    InventoryAuditItem(InventoryAudit inventoryAudit, Product product, StockBatch batch, BigDecimal expectedQuantity) {
        this.inventoryAudit = Objects.requireNonNull(inventoryAudit, "inventoryAudit");
        this.product = Objects.requireNonNull(product, "product");
        this.batch = batch;
        this.expectedQuantity = Objects.requireNonNull(expectedQuantity, "expectedQuantity");
    }

    public void recordCount(BigDecimal counted, BigDecimal systemQuantityNow, String countNotes, AppUser by, Instant at) {
        inventoryAudit.requireCounting();
        this.countedQuantity = Objects.requireNonNull(counted, "counted");
        this.expectedQuantity = Objects.requireNonNull(systemQuantityNow, "systemQuantityNow");
        this.notes = countNotes;
        this.countedBy = Objects.requireNonNull(by, "by");
        this.countedAt = Objects.requireNonNull(at, "at");
    }

    public boolean isCounted() {
        return countedQuantity != null;
    }

    /** Computed in Java too, so it is available before the row is re-read after a flush. */
    public BigDecimal difference() {
        return countedQuantity == null ? null : countedQuantity.subtract(expectedQuantity);
    }
}
