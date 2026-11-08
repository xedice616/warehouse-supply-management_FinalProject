package com.anbar.wms.purchasing.model;

/**
 * {@code DRAFT → SUBMITTED → APPROVED → PARTIALLY_RECEIVED → RECEIVED}. A submitted order can be rejected back to
 * DRAFT. Cancelling is possible until the order is fully received; goods already received stay in stock.
 * {@code CLOSED} exists in the schema for a future short-close and isn't used yet.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    public boolean isReceivable() {
        return this == APPROVED || this == PARTIALLY_RECEIVED;
    }
}
