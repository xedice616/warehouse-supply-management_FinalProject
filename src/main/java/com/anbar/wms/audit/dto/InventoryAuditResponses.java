package com.anbar.wms.audit.dto;

import com.anbar.wms.audit.model.AuditScope;
import com.anbar.wms.audit.model.AuditStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class InventoryAuditResponses {

    private InventoryAuditResponses() {
    }

    /** {@code items} is present on single-audit responses only. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditResponse(
            long id,
            String auditNumber,
            WarehouseRef warehouse,
            AuditScope scope,
            AuditStatus status,
            boolean blind,
            String notes,
            int itemCount,
            int countedItems,
            int discrepancyItems,
            Step started,
            Step submitted,
            Step approved,
            Step completed,
            String rejectionReason,
            String adjustmentTransactionNumber,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<ItemResponse> items) {
    }

    /**
     * {@code expectedQuantity} is the system balance when the item was counted (before that, when it was added).
     * It and the discrepancy are hidden on blind audits until counting is over. {@code movementsSinceCount} and
     * {@code netChangeSinceCount} show stock activity after the count, which completion takes into account by
     * applying the discrepancy to the balance as it is then.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ItemResponse(
            long id,
            ProductRef product,
            BatchRef batch,
            BigDecimal expectedQuantity,
            BigDecimal countedQuantity,
            BigDecimal discrepancy,
            String notes,
            String countedBy,
            Instant countedAt,
            Long movementsSinceCount,
            BigDecimal netChangeSinceCount) {
    }

    public record Step(String by, Instant at) {
    }

    public record WarehouseRef(long id, String code, String name) {
    }

    public record ProductRef(long id, String sku, String name, String unitCode) {
    }

    public record BatchRef(long id, String batchNumber, LocalDate expirationDate) {
    }

    public record DiscrepancyRow(
            long auditId,
            String auditNumber,
            AuditStatus auditStatus,
            WarehouseRef warehouse,
            ProductRef product,
            BatchRef batch,
            BigDecimal expectedQuantity,
            BigDecimal countedQuantity,
            BigDecimal discrepancy,
            String notes,
            Instant countedAt) {
    }

    public record WarehouseStatistics(
            WarehouseRef warehouse,
            long audits,
            long completedAudits,
            long openAudits,
            long cancelledAudits,
            long countedItems,
            long discrepancyItems,
            BigDecimal surplusQuantity,
            BigDecimal shortageQuantity,
            Instant lastCompletedAt) {
    }

    /**
     * @param accuracyPercent share of counted items whose count matched the system, or null when nothing was counted
     */
    public record Statistics(
            long audits,
            Map<AuditStatus, Long> auditsByStatus,
            long countedItems,
            long discrepancyItems,
            BigDecimal accuracyPercent,
            BigDecimal surplusQuantity,
            BigDecimal shortageQuantity) {
    }
}
