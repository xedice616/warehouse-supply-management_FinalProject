package com.anbar.wms.catalog.service;

/** Stock facts the catalog needs, provided by the inventory module so the catalog doesn't depend on it. */
public interface ProductStockInfo {

    boolean productHasStockOnHand(long productId);

    boolean productHasStockHistory(long productId);
}
