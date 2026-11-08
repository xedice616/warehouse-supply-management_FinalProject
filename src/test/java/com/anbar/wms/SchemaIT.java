package com.anbar.wms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.catalog.repository.UnitOfMeasureRepository;
import com.anbar.wms.common.config.JpaAuditingConfig;
import com.anbar.wms.audit.model.AuditScope;
import com.anbar.wms.audit.entity.InventoryAudit;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.Role;
import com.anbar.wms.security.RoleCode;
import com.anbar.wms.identity.repository.RoleRepository;
import com.anbar.wms.inventory.entity.MovementLine;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.entity.StockMovement;
import com.anbar.wms.inventory.entity.StockTransaction;
import com.anbar.wms.inventory.model.StockTransactionType;
import com.anbar.wms.inventory.repository.StockMovementRepository;
import com.anbar.wms.inventory.repository.StockTransactionRepository;
import com.anbar.wms.purchasing.entity.PurchaseOrder;
import com.anbar.wms.purchasing.repository.PurchaseOrderRepository;
import com.anbar.wms.support.AbstractIntegrationTest;
import com.anbar.wms.support.TestData;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies the Flyway schema itself: entity mappings round-trip, seed data exists, and the database rejects
 * invalid states on its own (constraints, privileges, triggers), independently of any service code.
 *
 * <p>Each test runs in a transaction that is rolled back, so tests leave no data behind. Statements that
 * violate a constraint are issued last, because a failed statement aborts the PostgreSQL transaction.
 */
@Transactional
class SchemaIT extends AbstractIntegrationTest {

    @Autowired
    private EntityManager em;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RoleRepository roles;

    @Autowired
    private UnitOfMeasureRepository unitsOfMeasure;

    @Autowired
    private PurchaseOrderRepository purchaseOrders;

    @Autowired
    private StockTransactionRepository stockTransactions;

    @Autowired
    private StockMovementRepository stockMovements;

    private TestData data;

    @BeforeEach
    void setUp() {
        data = new TestData(em);
    }

    // ================================================================== mappings and seed data

    @Test
    void seedsRolesAndUnitsOfMeasure() {
        assertThat(roles.findAllByOrderByCodeAsc()).extracting(Role::getCode)
                .containsExactlyElementsOf(Arrays.stream(RoleCode.values()).map(Enum::name).sorted().toList());
        assertThat(unitsOfMeasure.findByCode("KG")).get().extracting(UnitOfMeasure::getDecimalScale).isEqualTo(3);
        assertThat(unitsOfMeasure.findByCode("PCS")).get().extracting(UnitOfMeasure::getDecimalScale).isEqualTo(0);
    }

    @Test
    void auditingFillsWhoAndWhenAndStartsVersionAtZero() {
        UnitOfMeasure box = unitsOfMeasure.saveAndFlush(new UnitOfMeasure("crate", "Crate", 0));

        assertThat(box.getCode()).isEqualTo("CRATE");
        assertThat(box.getVersion()).isZero();
        assertThat(box.getCreatedBy()).isEqualTo(JpaAuditingConfig.SYSTEM_AUDITOR);
        assertThat(box.getUpdatedBy()).isEqualTo(JpaAuditingConfig.SYSTEM_AUDITOR);
        assertThat(box.getCreatedAt()).isNotNull().isEqualTo(box.getUpdatedAt());
    }

    @Test
    void purchaseOrderAggregateRoundTrips() {
        Product bolts = data.product();
        Product nuts = data.product();
        PurchaseOrder order = new PurchaseOrder("PO-T-0001", data.supplier(), data.warehouse(),
                Currency.getInstance("AZN"), LocalDate.of(2026, 11, 7));
        order.addLine(bolts, new BigDecimal("100"), new BigDecimal("0.25"));
        order.addLine(nuts, new BigDecimal("40"), new BigDecimal("0.10"));
        purchaseOrders.saveAndFlush(order);
        em.clear();

        PurchaseOrder loaded = purchaseOrders.findWithLinesById(order.getId()).orElseThrow();

        assertThat(loaded.getLines()).extracting(l -> l.getLineNo()).containsExactly(1, 2);
        assertThat(loaded.getLines().get(0).getReceivedQuantity()).isEqualByComparingTo("0");
        assertThat(loaded.totalAmount()).isEqualByComparingTo("29.00");
        assertThat(loaded.getCurrency().getCurrencyCode()).isEqualTo("AZN");
    }

    @Test
    void stockTransactionCascadesMovementsWithTheTransactionsUserAndTime() {
        AppUser storekeeper = data.user();
        Product product = data.product();
        Warehouse from = data.warehouse();
        Warehouse to = data.warehouse();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);

        StockTransaction transfer = new StockTransaction("TRF-T-0001", StockTransactionType.TRANSFER,
                null, null, "idem-1", storekeeper, now);
        transfer.addMovement(new MovementLine(MovementType.TRANSFER, product, null, new BigDecimal("5"),
                from, to, new BigDecimal("15"), new BigDecimal("5"), null));
        stockTransactions.save(transfer);
        em.flush();
        em.clear();

        List<StockMovement> lines = stockMovements.findByTransactionIdOrderByLineNoAsc(transfer.getId());

        assertThat(lines).singleElement().satisfies(m -> {
            assertThat(m.getLineNo()).isEqualTo(1);
            assertThat(m.getQuantity()).isEqualByComparingTo("5");
            assertThat(m.getPerformedBy().getId()).isEqualTo(storekeeper.getId());
            assertThat(m.getPerformedAt()).isEqualTo(now);
        });
        assertThat(stockTransactions.findByIdempotencyKey("idem-1")).isPresent();
    }

    @Test
    void countLineVarianceIsComputedByTheDatabase() {
        Product product = data.product();
        InventoryAudit count = data.persist(new InventoryAudit("CNT-T-0001", data.warehouse(), AuditScope.PARTIAL, true, null));
        count.addItem(product, null, new BigDecimal("10"));
        em.flush();
        AppUser counter = data.user();
        em.flush();

        jdbc.update("""
                UPDATE inventory_audit_item SET counted_quantity = 7, counted_by = ?, counted_at = now()
                WHERE inventory_audit_id = ?""", counter.getId(), count.getId());

        assertThat(jdbc.queryForObject("SELECT discrepancy FROM inventory_audit_item WHERE inventory_audit_id = ?",
                BigDecimal.class, count.getId())).isEqualByComparingTo("-3");
    }

    // ================================================================== constraints reject invalid states

    @Nested
    class Constraints {

        @Test
        void expiryTrackedProductMustBeBatchTracked() {
            assertViolates("ck_product_expiry_requires_batch", () -> jdbc.update("""
                    UPDATE product SET batch_tracked = FALSE, expiry_tracked = TRUE WHERE id = ?""",
                    flushed(data.product()).getId()));
        }

        @Test
        void categoryCannotBeItsOwnParent() {
            Long id = flushed(data.category()).getId();
            assertViolates("ck_product_category_not_own_parent",
                    () -> jdbc.update("UPDATE product_category SET parent_id = id WHERE id = ?", id));
        }

        @Test
        void transferMustUseTwoDifferentWarehouses() {
            Fixture f = fixture();
            assertViolates("ck_stock_movement_endpoints", () -> insertMovement(f, "TRANSFER", f.warehouse, f.warehouse, null));
        }

        @Test
        void receiptHasNoSourceWarehouse() {
            Fixture f = fixture();
            assertViolates("ck_stock_movement_endpoints", () -> insertMovement(f, "INBOUND", f.warehouse, null, null));
        }

        @Test
        void movementUserMustMatchItsTransaction() {
            Fixture f = fixture();
            AppUser someoneElse = flushed(data.user());
            assertViolates("fk_stock_movement_transaction", () -> jdbc.update("""
                    INSERT INTO stock_movement (id, transaction_id, line_no, movement_type, product_id, quantity,
                        destination_warehouse_id, destination_balance_after, performed_by, performed_at)
                    VALUES (nextval('stock_movement_seq'), ?, 2, 'INBOUND', ?, 1, ?, 1, ?, ?)""",
                    f.transactionId, f.product.getId(), f.warehouse.getId(), someoneElse.getId(), f.performedAt));
        }

        @Test
        void movementBatchMustBelongToTheMovedProduct() {
            Fixture f = fixture();
            StockBatch otherProductsBatch = flushed(data.batch(data.batchTrackedProduct()));
            assertViolates("fk_stock_movement_batch",
                    () -> insertMovement(f, "INBOUND", null, f.warehouse, otherProductsBatch.getId()));
        }

        @Test
        void balanceCanNeverBeNegative() {
            Fixture f = fixture();
            assertViolates("ck_inventory_balance_non_negative", () -> jdbc.update("""
                    INSERT INTO inventory_balance (id, warehouse_id, product_id, quantity)
                    VALUES (nextval('inventory_balance_seq'), ?, ?, -1)""", f.warehouse.getId(), f.product.getId()));
        }

        @Test
        void balanceWithoutBatchIsUniquePerWarehouseAndProduct() {
            Fixture f = fixture();
            String insert = """
                    INSERT INTO inventory_balance (id, warehouse_id, product_id, quantity, total_received)
                    VALUES (nextval('inventory_balance_seq'), ?, ?, 1, 1)""";
            jdbc.update(insert, f.warehouse.getId(), f.product.getId());
            assertViolates("uq_inventory_balance_key", () -> jdbc.update(insert, f.warehouse.getId(), f.product.getId()));
        }

        @Test
        void transactionCanBeReversedOnlyOnce() {
            Fixture f = fixture();
            String reversal = """
                    INSERT INTO stock_transaction (id, transaction_number, type, reversal_of_id, performed_by, performed_at)
                    VALUES (nextval('stock_transaction_seq'), ?, 'REVERSAL', ?, ?, now())""";
            jdbc.update(reversal, "REV-T-1", f.transactionId, f.user.getId());
            assertViolates("uq_stock_transaction_reversal_of",
                    () -> jdbc.update(reversal, "REV-T-2", f.transactionId, f.user.getId()));
        }

        @Test
        void approvedPurchaseOrderNeedsAnApprover() {
            PurchaseOrder order = flushed(new PurchaseOrder("PO-T-0100", data.supplier(), data.warehouse(),
                    Currency.getInstance("AZN"), LocalDate.of(2026, 11, 7)));
            assertViolates("ck_purchase_order_approved_states",
                    () -> jdbc.update("UPDATE purchase_order SET status = 'APPROVED' WHERE id = ?", order.getId()));
        }

        @Test
        void cannotReceiveMoreThanOrdered() {
            PurchaseOrder order = new PurchaseOrder("PO-T-0101", data.supplier(), data.warehouse(),
                    Currency.getInstance("AZN"), LocalDate.of(2026, 11, 7));
            order.addLine(data.product(), new BigDecimal("10"), BigDecimal.ONE);
            flushed(order);
            assertViolates("ck_purchase_order_line_received", () -> jdbc.update(
                    "UPDATE purchase_order_line SET received_quantity = 10.0001 WHERE purchase_order_id = ?",
                    order.getId()));
        }

        @Test
        void countedQuantityNeedsWhoAndWhen() {
            InventoryAudit count = data.persist(new InventoryAudit("CNT-T-0100", data.warehouse(), AuditScope.FULL, true, null));
            count.addItem(data.product(), null, BigDecimal.ONE);
            em.flush();
            assertViolates("ck_inventory_count_line_counted_triple", () -> jdbc.update(
                    "UPDATE inventory_audit_item SET counted_quantity = 1 WHERE inventory_audit_id = ?", count.getId()));
        }
    }

    // ================================================================== the ledger is append-only

    @Nested
    class AppendOnlyLedger {

        @Test
        void applicationRoleCannotUpdateMovements() {
            fixture();
            assertPermissionDenied(() -> jdbc.update("UPDATE stock_movement SET quantity = quantity + 1"));
        }

        @Test
        void applicationRoleCannotDeleteTransactions() {
            fixture();
            assertPermissionDenied(() -> jdbc.update("DELETE FROM stock_transaction"));
        }

        @Test
        void applicationRoleCannotDeleteBalances() {
            assertPermissionDenied(() -> jdbc.update("DELETE FROM inventory_balance"));
        }

        @Test
        void triggerStopsEvenTheSchemaOwner(@Autowired Flyway flyway) {
            DataSource ownerDataSource = flyway.getConfiguration().getDataSource();
            JdbcTemplate owner = new JdbcTemplate(ownerDataSource);
            TransactionTemplate ownerTx = new TransactionTemplate(new DataSourceTransactionManager(ownerDataSource));

            assertThatThrownBy(() -> ownerTx.executeWithoutResult(status -> {
                status.setRollbackOnly();
                Long movementId = insertLedgerRowAsOwner(owner);
                owner.update("UPDATE stock_movement SET quantity = 999 WHERE id = ?", movementId);
            })).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");

            assertThatThrownBy(() -> owner.execute("TRUNCATE stock_movement"))
                    .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        }
    }

    // ================================================================== helpers

    /** A user, product and warehouse plus one committed-in-this-transaction RECEIPT transaction. */
    private record Fixture(AppUser user, Product product, Warehouse warehouse, Long transactionId, Timestamp performedAt) {
    }

    private Fixture fixture() {
        AppUser user = data.user();
        Product product = data.product();
        Warehouse warehouse = data.warehouse();
        em.flush();
        Timestamp performedAt = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Long transactionId = jdbc.queryForObject("""
                INSERT INTO stock_transaction (id, transaction_number, type, performed_by, performed_at)
                VALUES (nextval('stock_transaction_seq'), ?, 'INBOUND', ?, ?)
                RETURNING id""", Long.class, "RCV-" + UUID.randomUUID().toString().substring(0, 13),
                user.getId(), performedAt);
        jdbc.update("""
                INSERT INTO stock_movement (id, transaction_id, line_no, movement_type, product_id, quantity,
                    destination_warehouse_id, destination_balance_after, performed_by, performed_at)
                VALUES (nextval('stock_movement_seq'), ?, 1, 'INBOUND', ?, 10, ?, 10, ?, ?)""",
                transactionId, product.getId(), warehouse.getId(), user.getId(), performedAt);
        return new Fixture(user, product, warehouse, transactionId, performedAt);
    }

    private int insertMovement(Fixture f, String type, Warehouse source, Warehouse destination, Long batchId) {
        return jdbc.update("""
                INSERT INTO stock_movement (id, transaction_id, line_no, movement_type, product_id, batch_id, quantity,
                    source_warehouse_id, destination_warehouse_id, source_balance_after, destination_balance_after,
                    performed_by, performed_at)
                VALUES (nextval('stock_movement_seq'), ?, 2, ?, ?, ?, 1, ?, ?, ?, ?, ?, ?)""",
                f.transactionId, type, f.product.getId(), batchId,
                source == null ? null : source.getId(), destination == null ? null : destination.getId(),
                source == null ? null : BigDecimal.ZERO, destination == null ? null : BigDecimal.ONE,
                f.user.getId(), f.performedAt);
    }

    /** Minimal ledger fixture written by the owner role in its own (rolled back) transaction. */
    private static Long insertLedgerRowAsOwner(JdbcTemplate owner) {
        Long userId = owner.queryForObject("""
                INSERT INTO app_user (id, username, email, password_hash, full_name, status,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (nextval('app_user_seq'), 'owner-test', 'owner-test@example.test', 'x', 'Owner Test', 'ACTIVE',
                    0, now(), 'test', now(), 'test') RETURNING id""", Long.class);
        Long categoryId = owner.queryForObject("""
                INSERT INTO product_category (id, name, version, created_at, created_by, updated_at, updated_by)
                VALUES (nextval('product_category_seq'), 'Owner test', 0, now(), 'test', now(), 'test') RETURNING id""",
                Long.class);
        Long productId = owner.queryForObject("""
                INSERT INTO product (id, sku, name, category_id, uom_id, min_stock_quantity, purchase_price,
                    version, created_at, created_by, updated_at, updated_by)
                VALUES (nextval('product_seq'), 'OWNER-TEST', 'Owner test', ?,
                    (SELECT id FROM unit_of_measure WHERE code = 'PCS'), 0, 0, 0, now(), 'test', now(), 'test') RETURNING id""",
                Long.class, categoryId);
        Long warehouseId = owner.queryForObject("""
                INSERT INTO warehouse (id, code, name, status, version, created_at, created_by, updated_at, updated_by)
                VALUES (nextval('warehouse_seq'), 'OWNER-TEST', 'Owner test', 'ACTIVE', 0, now(), 'test', now(), 'test')
                RETURNING id""", Long.class);
        Timestamp at = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
        Long transactionId = owner.queryForObject("""
                INSERT INTO stock_transaction (id, transaction_number, type, performed_by, performed_at)
                VALUES (nextval('stock_transaction_seq'), 'OWNER-TEST', 'INBOUND', ?, ?) RETURNING id""",
                Long.class, userId, at);
        return owner.queryForObject("""
                INSERT INTO stock_movement (id, transaction_id, line_no, movement_type, product_id, quantity,
                    destination_warehouse_id, destination_balance_after, performed_by, performed_at)
                VALUES (nextval('stock_movement_seq'), ?, 1, 'INBOUND', ?, 1, ?, 1, ?, ?) RETURNING id""",
                Long.class, transactionId, productId, warehouseId, userId, at);
    }

    private <T> T flushed(T entity) {
        if (!em.contains(entity)) {
            em.persist(entity);
        }
        em.flush();
        return entity;
    }

    private static void assertViolates(String constraint, Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static void assertPermissionDenied(Runnable statement) {
        assertThatThrownBy(statement::run)
                .isInstanceOf(DataAccessException.class)
                .satisfies(ex -> assertThat(NestedExceptionUtils.getMostSpecificCause(ex)).hasMessageContaining("permission denied"));
    }
}
