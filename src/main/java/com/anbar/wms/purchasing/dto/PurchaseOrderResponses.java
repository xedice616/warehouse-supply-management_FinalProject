package com.anbar.wms.purchasing.dto;

import com.anbar.wms.inventory.dto.StockMovementResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class PurchaseOrderResponses {

    private PurchaseOrderResponses() {
    }

    public record PurchaseOrderResponse(
            long id,
            String orderNumber,
            Ref supplier,
            Ref destinationWarehouse,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            String status,
            String currency,
            BigDecimal totalAmount,
            String notes,
            String createdBy,
            String approvedBy,
            Instant approvedAt,
            String rejectionReason,
            String cancellationReason,
            List<ItemResponse> items,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    public record ItemResponse(
            long id,
            int lineNo,
            ProductRef product,
            BigDecimal orderedQuantity,
            BigDecimal receivedQuantity,
            BigDecimal outstandingQuantity,
            BigDecimal unitPrice,
            BigDecimal totalPrice,
            String notes) {
    }

    /**
     * @param replayed true when the Idempotency-Key was seen before: nothing new was received, this is the original
     */
    public record ReceiptResponse(
            long receiptId,
            String orderNumber,
            String orderStatus,
            boolean replayed,
            String receivedBy,
            Instant receivedAt,
            StockMovementResponse movement) {
    }

    /** One group of the purchase summary report. */
    public record SummaryRow(String key, String label, long orderCount, BigDecimal totalAmount) {
    }

    public record Ref(long id, String code, String name) {
    }

    public record ProductRef(long id, String sku, String name, String unitCode) {
    }
}
