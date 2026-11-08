package com.anbar.wms.audit.entity;

import com.anbar.wms.audit.model.AuditScope;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.AuditableEntity;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.entity.StockTransaction;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A physical count of one warehouse. Each item's expected quantity is the system balance at the moment the item is
 * counted, so stock movements before or after that moment don't distort the discrepancy (see {@link InventoryAuditItem}).
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryAudit extends AuditableEntity {

    @Column(name = "audit_number", nullable = false, length = 32, updatable = false)
    private String auditNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 20)
    private AuditScope scope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private AuditStatus status;

    @Column(name = "blind", nullable = false)
    private boolean blind;

    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by")
    private AppUser startedBy;

    @Column(name = "started_at")
    private Instant startedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submitted_by")
    private AppUser submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by")
    private AppUser completedBy;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "adjustment_transaction_id")
    private StockTransaction adjustmentTransaction;

    @OneToMany(mappedBy = "inventoryAudit", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<InventoryAuditItem> items = new ArrayList<>();

    public InventoryAudit(String auditNumber, Warehouse warehouse, AuditScope scope, boolean blind, String notes) {
        this.auditNumber = Objects.requireNonNull(auditNumber, "auditNumber");
        this.warehouse = Objects.requireNonNull(warehouse, "warehouse");
        this.scope = Objects.requireNonNull(scope, "scope");
        this.blind = blind;
        this.notes = notes;
        this.status = AuditStatus.DRAFT;
    }

    public InventoryAuditItem addItem(Product product, StockBatch batch, BigDecimal expectedQuantity) {
        requireStatus("have items added", AuditStatus.DRAFT, AuditStatus.IN_PROGRESS);
        if (findItem(product, batch).isPresent()) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE, "This product and batch are already on the audit",
                    Map.of("field", "productId"));
        }
        InventoryAuditItem item = new InventoryAuditItem(this, product, batch, expectedQuantity);
        items.add(item);
        return item;
    }

    public Optional<InventoryAuditItem> findItem(Product product, StockBatch batch) {
        return items.stream()
                .filter(i -> i.getProduct().equals(product) && Objects.equals(i.getBatch(), batch))
                .findFirst();
    }

    public void start(AppUser user, Instant at) {
        requireStatus("started", AuditStatus.DRAFT);
        startedBy = user;
        startedAt = at;
        status = AuditStatus.IN_PROGRESS;
    }

    public void requireCounting() {
        requireStatus("counted", AuditStatus.IN_PROGRESS);
    }

    public void submit(AppUser user, Instant at) {
        requireStatus("submitted", AuditStatus.IN_PROGRESS);
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "The audit has no items");
        }
        long uncounted = items.stream().filter(i -> !i.isCounted()).count();
        if (uncounted > 0) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION,
                    "%d item(s) have not been counted yet".formatted(uncounted), Map.of("uncounted", uncounted));
        }
        submittedBy = user;
        submittedAt = at;
        rejectionReason = null;
        status = AuditStatus.PENDING_APPROVAL;
    }

    public void approve(AppUser user, Instant at) {
        requireStatus("approved", AuditStatus.PENDING_APPROVAL);
        approvedBy = user;
        approvedAt = at;
        status = AuditStatus.APPROVED;
    }

    /** Back to counting; recorded counts stay and can be corrected. */
    public void reject(String reason) {
        requireStatus("rejected", AuditStatus.PENDING_APPROVAL);
        rejectionReason = Objects.requireNonNull(reason, "reason");
        submittedBy = null;
        submittedAt = null;
        status = AuditStatus.IN_PROGRESS;
    }

    public void complete(AppUser user, Instant at, StockTransaction adjustments) {
        requireStatus("completed", AuditStatus.APPROVED);
        completedBy = user;
        completedAt = at;
        adjustmentTransaction = adjustments;
        status = AuditStatus.COMPLETED;
    }

    public void requireCompletable() {
        requireStatus("completed", AuditStatus.APPROVED);
    }

    /** Counting is over once submitted: from then on system quantities may be shown on blind audits. */
    public boolean isCountingOver() {
        return EnumSet.of(AuditStatus.PENDING_APPROVAL, AuditStatus.APPROVED, AuditStatus.COMPLETED, AuditStatus.CANCELLED)
                .contains(status);
    }

    public void cancel() {
        requireStatus("cancelled", AuditStatus.DRAFT, AuditStatus.IN_PROGRESS, AuditStatus.PENDING_APPROVAL);
        status = AuditStatus.CANCELLED;
    }

    public List<InventoryAuditItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    private void requireStatus(String action, AuditStatus first, AuditStatus... others) {
        if (!EnumSet.of(first, others).contains(status)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "A %s audit cannot be %s".formatted(status, action), Map.of("status", status.name()));
        }
    }
}
