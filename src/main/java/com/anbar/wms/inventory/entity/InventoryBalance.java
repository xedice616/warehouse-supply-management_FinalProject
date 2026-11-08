package com.anbar.wms.inventory.entity;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.entity.BaseEntity;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Quantity on hand of one product (and batch) in one warehouse: a projection of the stock ledger.
 *
 * <p><b>Read-only through JPA.</b> Rows are created and updated exclusively by the inventory posting code with
 * single atomic SQL statements in the same transaction as the movement insert, which is what keeps
 * {@code balance = Σ ledger} and prevents lost updates. There is deliberately no public constructor or setter.
 * {@code version} is a plain column here (incremented by that SQL), not a JPA {@code @Version}.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryBalance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private Warehouse warehouse;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private StockBatch batch;

    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    /** Everything that ever entered this warehouse for this product and batch; never less than {@code quantity}. */
    @Column(name = "total_received", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalReceived;

    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
