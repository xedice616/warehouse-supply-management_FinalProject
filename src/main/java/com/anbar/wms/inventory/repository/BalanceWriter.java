package com.anbar.wms.inventory.repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The only code that changes {@code inventory_balance}. Each method is a single SQL statement inside the caller's
 * transaction:
 *
 * <ul>
 *   <li>{@link #decrease} subtracts only if enough stock is there ({@code WHERE quantity >= :qty}). The UPDATE takes
 *       the row lock and PostgreSQL re-evaluates the condition against the latest committed row after any wait, so two
 *       concurrent withdrawals can never both pass on the same units. No row back means insufficient stock.</li>
 *   <li>{@link #increase} inserts the row or adds to it ({@code ON CONFLICT ... DO UPDATE}), and also counts the
 *       quantity in {@code total_received}.</li>
 *   <li>{@link #lockBatchesForAllocation} locks a product's batch rows in one warehouse, earliest expiry first, for FEFO.</li>
 * </ul>
 *
 * All locks are taken in one canonical order: warehouse, product, expiry (unknown last), batch number, batch id.
 * Only the inventory posting service calls this.
 */
@Repository
public class BalanceWriter {

    private static final String DECREASE = """
            UPDATE inventory_balance
               SET quantity = quantity - :quantity, version = version + 1, updated_at = now()
             WHERE warehouse_id = :warehouseId AND product_id = :productId AND %s AND quantity >= :quantity
            RETURNING quantity""";

    private static final String INCREASE = """
            INSERT INTO inventory_balance (id, warehouse_id, product_id, batch_id, quantity, total_received)
            VALUES (nextval('inventory_balance_seq'), :warehouseId, :productId, :batchId, :quantity, :quantity)
            ON CONFLICT ON CONSTRAINT uq_inventory_balance_key DO UPDATE
               SET quantity = inventory_balance.quantity + EXCLUDED.quantity,
                   total_received = inventory_balance.total_received + EXCLUDED.quantity,
                   version = inventory_balance.version + 1,
                   updated_at = now()
            RETURNING quantity""";

    private static final String AVAILABLE = """
            SELECT quantity FROM inventory_balance
             WHERE warehouse_id = :warehouseId AND product_id = :productId AND %s""";

    private static final String LOCK_BATCHES = """
            SELECT b.batch_id, b.quantity
              FROM inventory_balance b
              JOIN stock_batch s ON s.id = b.batch_id
             WHERE b.warehouse_id = :warehouseId AND b.product_id = :productId AND b.batch_id IS NOT NULL
               %s
             ORDER BY s.expires_on ASC NULLS LAST, s.batch_number, s.id
               FOR UPDATE OF b""";

    private final NamedParameterJdbcTemplate jdbc;

    public BalanceWriter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the balance after the decrease, or empty if less than {@code quantity} is on hand */
    public Optional<BigDecimal> decrease(long warehouseId, long productId, Long batchId, BigDecimal quantity) {
        List<BigDecimal> after = jdbc.queryForList(DECREASE.formatted(batchCondition(batchId)),
                params(warehouseId, productId, batchId, quantity), BigDecimal.class);
        return after.stream().findFirst();
    }

    /** @return the balance after the increase */
    public BigDecimal increase(long warehouseId, long productId, Long batchId, BigDecimal quantity) {
        return jdbc.queryForObject(INCREASE, params(warehouseId, productId, batchId, quantity), BigDecimal.class);
    }

    public BigDecimal available(long warehouseId, long productId, Long batchId) {
        return jdbc.queryForList(AVAILABLE.formatted(batchCondition(batchId)),
                        params(warehouseId, productId, batchId, BigDecimal.ZERO), BigDecimal.class)
                .stream().findFirst().orElse(BigDecimal.ZERO);
    }

    /**
     * Locks every batch row of the product in the warehouse ({@code FOR UPDATE}), in FEFO order, and returns those
     * that can be allocated: quantity above zero, and not expired before {@code usableFrom} unless it is null.
     * Concurrent allocations of the same product queue here, so two of them can never pick the same units.
     */
    public List<BatchStock> lockBatchesForAllocation(long warehouseId, long productId, LocalDate usableFrom) {
        String filter = usableFrom == null
                ? "AND b.quantity > 0"
                : "AND b.quantity > 0 AND (s.expires_on IS NULL OR s.expires_on >= :usableFrom)";
        MapSqlParameterSource params = params(warehouseId, productId, null, BigDecimal.ZERO)
                .addValue("usableFrom", usableFrom, Types.DATE);
        return jdbc.query(LOCK_BATCHES.formatted(filter), params,
                (rs, row) -> new BatchStock(rs.getLong("batch_id"), rs.getBigDecimal("quantity")));
    }

    /** Locks all batch rows of the product in a warehouse in canonical order, before any later row lock in it. */
    public void lockProductRows(long warehouseId, long productId) {
        jdbc.query(LOCK_BATCHES.formatted(""), params(warehouseId, productId, null, BigDecimal.ZERO), rs -> null);
    }

    private static String batchCondition(Long batchId) {
        return batchId == null ? "batch_id IS NULL" : "batch_id = :batchId";
    }

    private static MapSqlParameterSource params(long warehouseId, long productId, Long batchId, BigDecimal quantity) {
        return new MapSqlParameterSource()
                .addValue("warehouseId", warehouseId)
                .addValue("productId", productId)
                .addValue("batchId", batchId, Types.BIGINT)
                .addValue("quantity", quantity);
    }

    public record BatchStock(long batchId, BigDecimal quantity) {
    }
}
