package com.anbar.wms.inventory.dto;

import com.anbar.wms.inventory.model.MovementType;
import java.time.LocalDate;

/**
 * History filters; all optional and combined with AND. {@code warehouseId} matches movements leaving or entering
 * the warehouse. {@code from}/{@code to} are inclusive calendar dates in the business time zone.
 */
public record MovementSearch(
        Long productId,
        Long batchId,
        Long warehouseId,
        MovementType type,
        Long supplierId,
        String referenceNumber,
        LocalDate from,
        LocalDate to) {

    public MovementSearch withType(MovementType newType) {
        return new MovementSearch(productId, batchId, warehouseId, newType, supplierId, referenceNumber, from, to);
    }
}
