package com.anbar.wms.purchasing.entity;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.AuditableEntity;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.purchasing.model.PurchaseOrderStatus;
import com.anbar.wms.supplier.entity.Supplier;
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
import jakarta.persistence.OrderBy;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * An order to a supplier for delivery into one warehouse; the aggregate root of its lines. Status changes go
 * through the methods below, which reject transitions the workflow doesn't allow. The total is always computed
 * from the lines, never stored or accepted from a client.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PurchaseOrder extends AuditableEntity {

    private static final Set<PurchaseOrderStatus> CANCELLABLE = EnumSet.of(PurchaseOrderStatus.DRAFT,
            PurchaseOrderStatus.SUBMITTED, PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED);

    @Column(name = "po_number", nullable = false, length = 32, updatable = false)
    private String poNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "currency", nullable = false, length = 3)
    private Currency currency;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "notes", length = 1000)
    private String notes;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private AppUser approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    public PurchaseOrder(String poNumber, Supplier supplier, Warehouse warehouse, Currency currency, LocalDate orderDate) {
        this.poNumber = Objects.requireNonNull(poNumber, "poNumber");
        this.status = PurchaseOrderStatus.DRAFT;
        updateHeader(supplier, warehouse, currency, orderDate, null, null);
    }

    public void updateHeader(Supplier supplier, Warehouse warehouse, Currency currency, LocalDate orderDate,
                             LocalDate expectedDeliveryDate, String notes) {
        requireStatus(PurchaseOrderStatus.DRAFT, "edited");
        this.supplier = Objects.requireNonNull(supplier, "supplier");
        this.warehouse = Objects.requireNonNull(warehouse, "warehouse");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.orderDate = Objects.requireNonNull(orderDate, "orderDate");
        this.expectedDeliveryDate = expectedDeliveryDate;
        this.notes = notes;
    }

    /** Replaces all lines of a draft order, numbered in the given order. */
    public void replaceLines(List<LineDraft> drafts) {
        requireStatus(PurchaseOrderStatus.DRAFT, "edited");
        lines.clear();
        for (LineDraft draft : drafts) {
            lines.add(new PurchaseOrderLine(this, lines.size() + 1, draft.product(), draft.orderedQuantity(),
                    draft.unitPrice(), draft.notes()));
        }
    }

    public PurchaseOrderLine addLine(Product product, BigDecimal orderedQuantity, BigDecimal unitPrice) {
        PurchaseOrderLine line = new PurchaseOrderLine(this, lines.size() + 1, product, orderedQuantity, unitPrice, null);
        lines.add(line);
        return line;
    }

    public void submit() {
        requireStatus(PurchaseOrderStatus.DRAFT, "submitted");
        if (lines.isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "A purchase order needs at least one item");
        }
        rejectionReason = null;
        status = PurchaseOrderStatus.SUBMITTED;
    }

    public void approve(AppUser approver, Instant at) {
        requireStatus(PurchaseOrderStatus.SUBMITTED, "approved");
        approvedBy = Objects.requireNonNull(approver, "approver");
        approvedAt = Objects.requireNonNull(at, "at");
        status = PurchaseOrderStatus.APPROVED;
    }

    public void reject(String reason) {
        requireStatus(PurchaseOrderStatus.SUBMITTED, "rejected");
        rejectionReason = Objects.requireNonNull(reason, "reason");
        status = PurchaseOrderStatus.DRAFT;
    }

    public void cancel(String reason) {
        if (!CANCELLABLE.contains(status)) {
            throw invalidTransition("cancelled");
        }
        cancellationReason = Objects.requireNonNull(reason, "reason");
        status = PurchaseOrderStatus.CANCELLED;
    }

    /** Called after each receipt: RECEIVED once every line is complete, otherwise PARTIALLY_RECEIVED. */
    public void refreshReceivingStatus() {
        if (!status.isReceivable()) {
            throw invalidTransition("received");
        }
        status = lines.stream().allMatch(PurchaseOrderLine::isFullyReceived)
                ? PurchaseOrderStatus.RECEIVED
                : PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }

    public BigDecimal totalAmount() {
        return lines.stream().map(PurchaseOrderLine::lineAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public List<PurchaseOrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    private void requireStatus(PurchaseOrderStatus expected, String action) {
        if (status != null && status != expected) {
            throw invalidTransition(action);
        }
    }

    private BusinessException invalidTransition(String action) {
        return new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                "A %s purchase order cannot be %s".formatted(status, action),
                Map.of("status", status.name()));
    }

    public record LineDraft(Product product, BigDecimal orderedQuantity, BigDecimal unitPrice, String notes) {
    }
}
