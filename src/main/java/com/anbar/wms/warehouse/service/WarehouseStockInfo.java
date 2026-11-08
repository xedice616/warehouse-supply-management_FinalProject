package com.anbar.wms.warehouse.service;

/** Stock facts the warehouse module needs, provided by the inventory module. */
public interface WarehouseStockInfo {

    boolean warehouseHasStockOnHand(long warehouseId);
}
