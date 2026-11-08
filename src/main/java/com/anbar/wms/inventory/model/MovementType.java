package com.anbar.wms.inventory.model;

/** Kind of a single stock movement. The database fixes which warehouses each type touches. */
public enum MovementType {
    /** Into a destination only: the initial stock load. */
    OPENING_BALANCE,
    /** Into a destination only: goods received. */
    INBOUND,
    /** Out of a source only: goods issued or shipped. */
    OUTBOUND,
    /** Out of a source and into a different destination, atomically. */
    TRANSFER,
    /** Into a destination (increase) or out of a source (decrease): an authorized correction. */
    ADJUSTMENT,
    /** Out of a source only: damaged, expired or otherwise unusable goods. */
    WRITE_OFF,
    /** Out of a source only: goods sent back to their supplier. */
    RETURN_TO_SUPPLIER,
    /** Mirror image of a reversed movement. */
    REVERSAL
}
