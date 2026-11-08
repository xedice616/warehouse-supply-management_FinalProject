package com.anbar.wms.inventory.model;

/** Kind of stock document; each document has one or more {@link StockMovement} lines. */
public enum StockTransactionType {
    OPENING_BALANCE,
    INBOUND,
    OUTBOUND,
    TRANSFER,
    ADJUSTMENT,
    WRITE_OFF,
    RETURN_TO_SUPPLIER,
    REVERSAL
}
