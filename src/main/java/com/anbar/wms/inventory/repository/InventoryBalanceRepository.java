package com.anbar.wms.inventory.repository;

import com.anbar.wms.common.repository.ReadOnlyRepository;
import com.anbar.wms.inventory.entity.InventoryBalance;
import com.anbar.wms.inventory.repository.view.StockViews.BatchQuantity;
import com.anbar.wms.inventory.repository.view.StockViews.ProductQuantity;
import com.anbar.wms.inventory.repository.view.StockViews.StockLine;
import com.anbar.wms.inventory.repository.view.StockViews.WarehouseQuantity;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Read access to the balance projection. Balances are written only by the inventory posting code, with atomic
 * SQL in the same transaction as the ledger insert; nothing here can change them.
 */
public interface InventoryBalanceRepository extends ReadOnlyRepository<InventoryBalance, Long> {

    @Query(value = """
            select new com.anbar.wms.inventory.repository.view.StockViews$StockLine(
                p.id, p.sku, p.name, u.code, bt.id, bt.batchNumber, bt.expiresOn, b.quantity, b.updatedAt)
            from InventoryBalance b join b.product p join p.unitOfMeasure u left join b.batch bt
            where b.warehouse.id = :warehouseId and (:includeZero = true or b.quantity > 0)""",
            countQuery = """
            select count(b) from InventoryBalance b
            where b.warehouse.id = :warehouseId and (:includeZero = true or b.quantity > 0)""")
    Page<StockLine> findStockLines(@Param("warehouseId") long warehouseId,
                                   @Param("includeZero") boolean includeZero,
                                   Pageable pageable);

    @Query(value = """
            select new com.anbar.wms.inventory.repository.view.StockViews$ProductQuantity(
                p.id, p.sku, p.name, u.code, sum(b.quantity), p.minStockQuantity)
            from InventoryBalance b join b.product p join p.unitOfMeasure u
            where b.warehouse.id = :warehouseId
            group by p.id, p.sku, p.name, u.code, p.minStockQuantity
            having (:includeZero = true or sum(b.quantity) > 0)""",
            countQuery = """
            select count(distinct b.product.id) from InventoryBalance b
            where b.warehouse.id = :warehouseId and (:includeZero = true or b.quantity > 0)""")
    Page<ProductQuantity> findProductTotals(@Param("warehouseId") long warehouseId,
                                            @Param("includeZero") boolean includeZero,
                                            Pageable pageable);

    @Query("""
            select new com.anbar.wms.inventory.repository.view.StockViews$WarehouseQuantity(
                w.id, w.code, w.name, sum(b.quantity))
            from InventoryBalance b join b.warehouse w
            where b.product.id = :productId and (:allWarehouses = true or w.id in :warehouseIds)
            group by w.id, w.code, w.name
            order by w.code""")
    List<WarehouseQuantity> findWarehouseTotals(@Param("productId") long productId,
                                                @Param("allWarehouses") boolean allWarehouses,
                                                @Param("warehouseIds") Collection<Long> warehouseIds);

    @Query("""
            select new com.anbar.wms.inventory.repository.view.StockViews$BatchQuantity(
                bt.id, bt.batchNumber, bt.expiresOn, b.quantity)
            from InventoryBalance b left join b.batch bt
            where b.warehouse.id = :warehouseId and b.product.id = :productId
            order by bt.expiresOn asc nulls last, bt.batchNumber""")
    List<BatchQuantity> findBatches(@Param("warehouseId") long warehouseId, @Param("productId") long productId);

    boolean existsByWarehouseIdAndQuantityGreaterThan(Long warehouseId, BigDecimal quantity);

    boolean existsByProductIdAndQuantityGreaterThan(Long productId, BigDecimal quantity);
}
