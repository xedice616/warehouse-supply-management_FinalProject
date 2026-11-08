package com.anbar.wms.support;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Puts stock in place the way a posting will in the inventory stage: one RECEIPT transaction, its movement,
 * and the matching balance upsert, so the ledger and the balance projection stay consistent.
 */
public final class StockLedgerFixture {

    private final JdbcTemplate jdbc;

    public StockLedgerFixture(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long batch(long productId, String batchNumber, LocalDate expiresOn) {
        return jdbc.queryForObject("""
                INSERT INTO stock_batch (id, product_id, batch_number, expires_on,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (nextval('stock_batch_seq'), ?, ?, ?, 0, now(), 'test', now(), 'test')
                RETURNING id""", Long.class, productId, batchNumber, expiresOn);
    }

    public void receive(long warehouseId, long productId, Long batchId, String quantity) {
        BigDecimal amount = new BigDecimal(quantity);
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'it-admin'", Long.class);
        Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO stock_transaction (id, transaction_number, type, performed_by, performed_at)
                VALUES (nextval('stock_transaction_seq'), ?, 'INBOUND', ?, ?) RETURNING id""",
                Long.class, "FIX-" + UUID.randomUUID().toString().substring(0, 12), userId, now);
        BigDecimal balanceAfter = jdbc.queryForObject("""
                INSERT INTO inventory_balance (id, warehouse_id, product_id, batch_id, quantity, total_received)
                VALUES (nextval('inventory_balance_seq'), ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_inventory_balance_key DO UPDATE
                    SET quantity = inventory_balance.quantity + EXCLUDED.quantity,
                        total_received = inventory_balance.total_received + EXCLUDED.quantity,
                        version = inventory_balance.version + 1,
                        updated_at = now()
                RETURNING quantity""", BigDecimal.class, warehouseId, productId, batchId, amount, amount);
        jdbc.update("""
                INSERT INTO stock_movement (id, transaction_id, line_no, movement_type, product_id, batch_id, quantity,
                    destination_warehouse_id, destination_balance_after, performed_by, performed_at)
                VALUES (nextval('stock_movement_seq'), ?, 1, 'INBOUND', ?, ?, ?, ?, ?, ?, ?)""",
                transactionId, productId, batchId, amount, warehouseId, balanceAfter, userId, now);
    }
}
