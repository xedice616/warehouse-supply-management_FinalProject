package com.anbar.wms.inventory.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A stock movement. {@code movementDate} is when it was posted (server time; movements can't be backdated);
 * {@code createdAt} is the same instant.
 *
 * <p>Posting endpoints add {@code allocations}: every ledger line the request produced. When FEFO spreads one request
 * over several batches, the top level holds the total quantity and {@code batch}/balances are per allocation.
 */
public record StockMovementResponse(
        long id,
        String movementNumber,
        String movementType,
        ProductRef product,
        BatchRef batch,
        BigDecimal quantity,
        WarehouseRef sourceWarehouse,
        WarehouseRef destinationWarehouse,
        BigDecimal sourceBalanceAfter,
        BigDecimal destinationBalanceAfter,
        BigDecimal unitCost,
        SupplierRef supplier,
        String referenceNumber,
        String reason,
        UserRef performedBy,
        Instant movementDate,
        Instant createdAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Allocation> allocations) {

    public StockMovementResponse withAllocations(BigDecimal total, List<Allocation> lines) {
        boolean single = lines.size() == 1;
        return new StockMovementResponse(id, movementNumber, movementType, product, single ? batch : null, total,
                sourceWarehouse, destinationWarehouse, single ? sourceBalanceAfter : null,
                single ? destinationBalanceAfter : null, unitCost, supplier, referenceNumber, reason, performedBy,
                movementDate, createdAt, lines);
    }

    public record Allocation(long movementId, BatchRef batch, BigDecimal quantity, BigDecimal sourceBalanceAfter,
                             BigDecimal destinationBalanceAfter) {
    }

    public record ProductRef(long id, String sku, String name, String unitCode) {
    }

    public record BatchRef(long id, String batchNumber, LocalDate expiresOn) {
    }

    public record WarehouseRef(long id, String code, String name) {
    }

    public record SupplierRef(long id, String code, String companyName) {
    }

    public record UserRef(long id, String username) {
    }
}
