package com.anbar.wms.purchasing.entity;

import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.time.Instant;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/** One processed receiving request against a purchase order, linked to the INBOUND stock document it posted. */
@Getter
@Entity
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GoodsReceipt extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false, updatable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "idempotency_key", nullable = false, length = 100, updatable = false)
    private String idempotencyKey;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_transaction_id", nullable = false, updatable = false)
    private StockTransaction stockTransaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "received_by", nullable = false, updatable = false)
    private AppUser receivedBy;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt;

    public GoodsReceipt(PurchaseOrder purchaseOrder, String idempotencyKey, StockTransaction stockTransaction,
                        AppUser receivedBy, Instant receivedAt) {
        this.purchaseOrder = Objects.requireNonNull(purchaseOrder, "purchaseOrder");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.stockTransaction = Objects.requireNonNull(stockTransaction, "stockTransaction");
        this.receivedBy = Objects.requireNonNull(receivedBy, "receivedBy");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
    }
}
