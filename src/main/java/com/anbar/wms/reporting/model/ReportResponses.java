package com.anbar.wms.reporting.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReportResponses {

    private ReportResponses() {
    }

    /** Report 1: one stock movement line with its product, warehouses, actor and reference. */
    public record MovementReportRow(
            long movementId,
            String movementNumber,
            String movementType,
            Instant movementDate,
            long productId,
            String sku,
            String productName,
            String unitCode,
            String batchNumber,
            BigDecimal quantity,
            String sourceWarehouseCode,
            String destinationWarehouseCode,
            Long performedByUserId,
            String performedByUsername,
            String referenceNumber) {
    }

    /**
     * Report 2: a product's movement volume over the period. Inbound counts quantity entering the scoped
     * warehouses, outbound counts quantity leaving them (a transfer inside the scope counts as both).
     */
    public record TopProductRow(
            long productId,
            String sku,
            String productName,
            String unitCode,
            BigDecimal inboundQuantity,
            BigDecimal outboundQuantity,
            BigDecimal totalVolume,
            long movementCount) {
    }

    /** Report 3: inbound vs outbound totals, with a per-period breakdown. */
    public record InOutStatistics(
            BigDecimal totalInbound,
            BigDecimal totalOutbound,
            BigDecimal netMovement,
            long movementCount,
            List<InOutPeriod> breakdown) {
    }

    public record InOutPeriod(
            LocalDate periodStart,
            BigDecimal inbound,
            BigDecimal outbound,
            BigDecimal net,
            long movementCount) {
    }

    /**
     * Report 4: inventory value. Cost basis is each batch's unit cost where known, otherwise the product's
     * purchase price, so the value is a quantity-weighted average cost (never the selling price).
     */
    public record ValuationRow(
            String key,
            String label,
            BigDecimal totalQuantity,
            BigDecimal totalValue) {
    }

    /** Report 5: activity and value per warehouse. valuePercent is this warehouse's share of total value. */
    public record WarehousePerformanceRow(
            long warehouseId,
            String code,
            String name,
            long totalProducts,
            BigDecimal totalQuantity,
            BigDecimal inventoryValue,
            BigDecimal inboundQuantity,
            BigDecimal outboundQuantity,
            long movementCount,
            BigDecimal valuePercent) {
    }

    /** Report 6: purchasing activity per supplier over the period (non-cancelled orders). */
    public record SupplierPerformanceRow(
            long supplierId,
            String code,
            String companyName,
            long totalOrders,
            BigDecimal totalPurchasedQuantity,
            BigDecimal totalPurchaseValue,
            long completedOrders,
            long partiallyReceivedOrders,
            long outstandingOrders) {
    }
}
