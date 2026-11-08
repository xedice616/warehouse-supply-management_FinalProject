package com.anbar.wms.warehouse.entity;

/** Only ACTIVE warehouses can appear in new purchase orders, movements or counts. */
public enum WarehouseStatus {
    ACTIVE,
    INACTIVE
}
