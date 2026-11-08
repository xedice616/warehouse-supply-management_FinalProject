package com.anbar.wms.inventory.entity;

import com.anbar.wms.inventory.model.StockTransactionType;
import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.supplier.entity.Supplier;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A posted stock document: the header of one or more {@link StockMovement} lines that were applied atomically.
 *
 * <p><b>Append-only.</b> Hibernate never issues UPDATEs for it ({@link Immutable}), the application's database role
 * has no UPDATE/DELETE privilege on the table, and a trigger rejects any modification by anyone.
 * Mistakes are corrected with a new {@link StockTransactionType#REVERSAL} transaction.
 *
 * <p>Documents that cause stock changes (goods receipts, inventory counts) reference their transaction;
 * the transaction doesn't reference them back.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockTransaction extends BaseEntity {

    @Column(name = "transaction_number", nullable = false, length = 32, updatable = false)
    private String transactionNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 32, updatable = false)
    private StockTransactionType type;

    @Column(name = "reason", length = 500, updatable = false)
    private String reason;

    /** The external document behind this movement: delivery note, invoice, work order. */
    @Column(name = "reference_number", length = 64, updatable = false)
    private String referenceNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", updatable = false)
    private Supplier supplier;

    /** The transaction this one compensates; set only for {@link StockTransactionType#REVERSAL}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_id", updatable = false)
    private StockTransaction reversalOf;

    /** Client-supplied key that makes retries of the same request return this transaction instead of posting twice. */
    @Column(name = "idempotency_key", length = 100, updatable = false)
    private String idempotencyKey;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private AppUser performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.PERSIST)
    @OrderBy("lineNo")
    private List<StockMovement> movements = new ArrayList<>();

    public StockTransaction(String transactionNumber, StockTransactionType type, String reason,
                            StockTransaction reversalOf, String idempotencyKey,
                            AppUser performedBy, Instant performedAt) {
        this(transactionNumber, type, reason, null, null, reversalOf, idempotencyKey, performedBy, performedAt);
    }

    public StockTransaction(String transactionNumber, StockTransactionType type, String reason,
                            String referenceNumber, Supplier supplier, StockTransaction reversalOf,
                            String idempotencyKey, AppUser performedBy, Instant performedAt) {
        this.referenceNumber = referenceNumber;
        this.supplier = supplier;
        if ((type == StockTransactionType.REVERSAL) != (reversalOf != null)) {
            throw new IllegalArgumentException("A REVERSAL transaction, and only a reversal, references the reversed one");
        }
        this.transactionNumber = Objects.requireNonNull(transactionNumber, "transactionNumber");
        this.type = Objects.requireNonNull(type, "type");
        this.reason = reason;
        this.reversalOf = reversalOf;
        this.idempotencyKey = idempotencyKey;
        this.performedBy = Objects.requireNonNull(performedBy, "performedBy");
        this.performedAt = Objects.requireNonNull(performedAt, "performedAt");
    }

    /**
     * Appends a movement line, numbered after the existing ones. The movement inherits this transaction's user and
     * timestamp (the database enforces that they match). Must be called before the transaction is persisted.
     */
    public StockMovement addMovement(MovementLine line) {
        StockMovement movement = new StockMovement(this, movements.size() + 1, line);
        movements.add(movement);
        return movement;
    }

    public List<StockMovement> getMovements() {
        return Collections.unmodifiableList(movements);
    }
}
