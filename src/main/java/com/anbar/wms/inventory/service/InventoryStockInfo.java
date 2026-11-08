package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.service.ProductStockInfo;
import com.anbar.wms.inventory.repository.InventoryBalanceRepository;
import com.anbar.wms.inventory.repository.StockMovementRepository;
import com.anbar.wms.warehouse.service.WarehouseStockInfo;
import java.math.BigDecimal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class InventoryStockInfo implements ProductStockInfo, WarehouseStockInfo {

    private final InventoryBalanceRepository balances;
    private final StockMovementRepository movements;

    public InventoryStockInfo(InventoryBalanceRepository balances, StockMovementRepository movements) {
        this.balances = balances;
        this.movements = movements;
    }

    @Override
    public boolean productHasStockOnHand(long productId) {
        return balances.existsByProductIdAndQuantityGreaterThan(productId, BigDecimal.ZERO);
    }

    @Override
    public boolean productHasStockHistory(long productId) {
        return movements.existsByProductId(productId);
    }

    @Override
    public boolean warehouseHasStockOnHand(long warehouseId) {
        return balances.existsByWarehouseIdAndQuantityGreaterThan(warehouseId, BigDecimal.ZERO);
    }
}
