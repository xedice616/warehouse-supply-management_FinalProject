package com.anbar.wms.inventory.entity;

import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * The values of one stock movement line, passed to {@link StockTransaction#addMovement(MovementLine)}.
 *
 * @param batch                   required for batch-tracked products, {@code null} otherwise
 * @param quantity                always positive; direction comes from source/destination
 * @param source                  warehouse stock leaves, or {@code null} for inbound types
 * @param destination             warehouse stock enters, or {@code null} for outbound types
 * @param sourceBalanceAfter      balance of (source, product, batch) after this movement; {@code null} without source
 * @param destinationBalanceAfter balance of (destination, product, batch) after; {@code null} without destination
 * @param unitCost                cost per unit, known for receipts; {@code null} otherwise
 */
public record MovementLine(
        MovementType type,
        Product product,
        StockBatch batch,
        BigDecimal quantity,
        Warehouse source,
        Warehouse destination,
        BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter,
        BigDecimal unitCost) {

    public MovementLine {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(product, "product");
        Objects.requireNonNull(quantity, "quantity");
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("Movement quantity must be positive");
        }
        if (source == null && destination == null) {
            throw new IllegalArgumentException("A movement needs a source, a destination, or both");
        }
    }
}
