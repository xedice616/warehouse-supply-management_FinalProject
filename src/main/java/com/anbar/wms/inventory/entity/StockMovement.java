package com.anbar.wms.inventory.entity;

import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * One line of the immutable stock ledger: a positive quantity of a product (and batch) leaving a source warehouse,
 * entering a destination warehouse, or both (transfer). Created only through {@link StockTransaction#addMovement}.
 *
 * <p>Carries the responsible user and timestamp of its transaction, so every ledger line is self-describing for
 * audit queries; a composite foreign key guarantees they equal the transaction's.
 *
 * <p><b>Append-only</b>, like {@link StockTransaction}.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockMovement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transaction_id", nullable = false, updatable = false)
    private StockTransaction transaction;

    @Column(name = "line_no", nullable = false, updatable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, length = 32, updatable = false)
    private MovementType movementType;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id", updatable = false)
    private StockBatch batch;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal quantity;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_warehouse_id", updatable = false)
    private Warehouse sourceWarehouse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "destination_warehouse_id", updatable = false)
    private Warehouse destinationWarehouse;

    @Column(name = "source_balance_after", precision = 19, scale = 4, updatable = false)
    private BigDecimal sourceBalanceAfter;

    @Column(name = "destination_balance_after", precision = 19, scale = 4, updatable = false)
    private BigDecimal destinationBalanceAfter;

    @Column(name = "unit_cost", precision = 19, scale = 4, updatable = false)
    private BigDecimal unitCost;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "performed_by", nullable = false, updatable = false)
    private AppUser performedBy;

    @Column(name = "performed_at", nullable = false, updatable = false)
    private Instant performedAt;

    StockMovement(StockTransaction transaction, int lineNo, MovementLine line) {
        this.transaction = transaction;
        this.lineNo = lineNo;
        this.movementType = line.type();
        this.product = line.product();
        this.batch = line.batch();
        this.quantity = line.quantity();
        this.sourceWarehouse = line.source();
        this.destinationWarehouse = line.destination();
        this.sourceBalanceAfter = line.sourceBalanceAfter();
        this.destinationBalanceAfter = line.destinationBalanceAfter();
        this.unitCost = line.unitCost();
        this.performedBy = transaction.getPerformedBy();
        this.performedAt = transaction.getPerformedAt();
    }
}
