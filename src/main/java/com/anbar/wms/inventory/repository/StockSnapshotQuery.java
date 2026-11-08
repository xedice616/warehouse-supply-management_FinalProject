package com.anbar.wms.inventory.repository;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class StockSnapshotQuery {

    private final NamedParameterJdbcTemplate jdbc;

    public StockSnapshotQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record StockedRow(long productId, Long batchId, BigDecimal quantity) {
    }

    /**
     * The balance of one (warehouse, product, batch), share-locked until the caller's transaction ends: a movement
     * of that stock waits instead of slipping in between the snapshot and the commit that records it.
     */
    public BigDecimal lockQuantity(long warehouseId, long productId, Long batchId) {
        List<BigDecimal> rows = jdbc.queryForList("""
                SELECT quantity FROM inventory_balance
                WHERE warehouse_id = :warehouseId AND product_id = :productId
                  AND batch_id IS NOT DISTINCT FROM CAST(:batchId AS BIGINT)
                FOR SHARE""", params(warehouseId, productId).addValue("batchId", batchId), BigDecimal.class);
        return rows.isEmpty() ? BigDecimal.ZERO : rows.getFirst();
    }

    /** Rows with stock in a warehouse, optionally for one product, in canonical lock order. */
    public List<StockedRow> stocked(long warehouseId, Long productId) {
        return jdbc.query("""
                SELECT b.product_id, b.batch_id, b.quantity
                FROM inventory_balance b LEFT JOIN stock_batch s ON s.id = b.batch_id
                WHERE b.warehouse_id = :warehouseId AND b.quantity > 0
                  AND (CAST(:productId AS BIGINT) IS NULL OR b.product_id = :productId)
                ORDER BY b.product_id, s.expires_on NULLS LAST, s.batch_number, b.batch_id NULLS FIRST""",
                params(warehouseId, productId),
                (rs, n) -> new StockedRow(rs.getLong("product_id"), (Long) rs.getObject("batch_id", Long.class),
                        rs.getBigDecimal("quantity")));
    }

    private static MapSqlParameterSource params(long warehouseId, Long productId) {
        return new MapSqlParameterSource().addValue("warehouseId", warehouseId).addValue("productId", productId);
    }
}
