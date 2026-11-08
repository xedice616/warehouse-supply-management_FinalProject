package com.anbar.wms.audit.repository;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Audit reports and the post-count activity indicator, read straight from the tables. */
@Repository
public class InventoryAuditQuery {

    private static final Map<String, String> DISCREPANCY_SORT = Map.of(
            "discrepancy", "abs(i.discrepancy)", "countedAt", "i.counted_at", "auditNumber", "a.audit_number",
            "sku", "p.sku");

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryAuditQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param warehouseIds null for every warehouse
     * @param from         inclusive lower bound on the audit's creation time, or null
     * @param to           exclusive upper bound on the audit's creation time, or null
     */
    public record Filter(Collection<Long> warehouseIds, Long warehouseId, String status, Instant from, Instant to) {
    }

    public record MovementsSince(long movements, BigDecimal netChange) {
    }

    public record DiscrepancyRow(long auditId, String auditNumber, String auditStatus, long warehouseId,
                                 String warehouseCode, String warehouseName, long productId, String sku,
                                 String productName, String unitCode, Long batchId, String batchNumber,
                                 LocalDate expirationDate, BigDecimal expectedQuantity, BigDecimal countedQuantity,
                                 BigDecimal discrepancy, String notes, Instant countedAt) {
    }

    public record WarehouseRow(long warehouseId, String warehouseCode, String warehouseName, long audits,
                               long completed, long open, long cancelled, long countedItems, long discrepancyItems,
                               BigDecimal surplus, BigDecimal shortage, Instant lastCompletedAt) {
    }

    public record Totals(Map<String, Long> auditsByStatus, long countedItems, long discrepancyItems,
                         BigDecimal surplus, BigDecimal shortage) {
    }

    /**
     * Ledger movements of each counted item's stock in the audit's warehouse after it was counted, excluding the
     * audit's own adjustment.
     */
    public Map<Long, MovementsSince> movementsSinceCount(long auditId) {
        Map<Long, MovementsSince> result = new HashMap<>();
        jdbc.query("""
                SELECT i.id, count(m.id) AS movements,
                       COALESCE(SUM(CASE WHEN m.destination_warehouse_id = a.warehouse_id THEN m.quantity
                                         ELSE -m.quantity END), 0) AS net
                FROM inventory_audit_item i
                JOIN inventory_audit a ON a.id = i.inventory_audit_id
                LEFT JOIN stock_movement m
                       ON m.product_id = i.product_id
                      AND m.batch_id IS NOT DISTINCT FROM i.batch_id
                      AND (m.source_warehouse_id = a.warehouse_id OR m.destination_warehouse_id = a.warehouse_id)
                      AND m.performed_at > i.counted_at
                      AND m.transaction_id IS DISTINCT FROM a.adjustment_transaction_id
                WHERE i.inventory_audit_id = :auditId AND i.counted_at IS NOT NULL
                GROUP BY i.id""", new MapSqlParameterSource("auditId", auditId),
                rs -> {
                    result.put(rs.getLong("id"), new MovementsSince(rs.getLong("movements"), rs.getBigDecimal("net")));
                });
        return result;
    }

    public Page<DiscrepancyRow> discrepancies(Filter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String from = """
                FROM inventory_audit_item i
                JOIN inventory_audit a ON a.id = i.inventory_audit_id
                JOIN warehouse w ON w.id = a.warehouse_id
                JOIN product p ON p.id = i.product_id
                JOIN unit_of_measure u ON u.id = p.uom_id
                LEFT JOIN stock_batch s ON s.id = i.batch_id
                """ + where(filter, params) + " AND i.discrepancy <> 0";
        Long total = jdbc.queryForObject("SELECT count(*) " + from, params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<DiscrepancyRow> rows = jdbc.query("""
                SELECT a.id AS audit_id, a.audit_number, a.status, w.id AS warehouse_id, w.code AS warehouse_code,
                       w.name AS warehouse_name, p.id AS product_id, p.sku, p.name AS product_name, u.code AS unit_code,
                       s.id AS batch_id, s.batch_number, s.expires_on, i.expected_quantity, i.counted_quantity,
                       i.discrepancy, i.notes, i.counted_at
                """ + from + " ORDER BY " + discrepancyOrder(pageable.getSort()) + " LIMIT :limit OFFSET :offset",
                params, (rs, n) -> new DiscrepancyRow(rs.getLong("audit_id"), rs.getString("audit_number"),
                        rs.getString("status"), rs.getLong("warehouse_id"), rs.getString("warehouse_code"),
                        rs.getString("warehouse_name"), rs.getLong("product_id"), rs.getString("sku"),
                        rs.getString("product_name"), rs.getString("unit_code"), rs.getObject("batch_id", Long.class),
                        rs.getString("batch_number"), rs.getObject("expires_on", LocalDate.class),
                        rs.getBigDecimal("expected_quantity"), rs.getBigDecimal("counted_quantity"),
                        rs.getBigDecimal("discrepancy"), rs.getString("notes"), instant(rs.getTimestamp("counted_at"))));
        return new PageImpl<>(rows, pageable, total);
    }

    public List<WarehouseRow> byWarehouse(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        return jdbc.query("""
                WITH items AS (
                    SELECT inventory_audit_id,
                           count(*) FILTER (WHERE counted_at IS NOT NULL) AS counted,
                           count(*) FILTER (WHERE discrepancy <> 0) AS discrepant,
                           COALESCE(SUM(discrepancy) FILTER (WHERE discrepancy > 0), 0) AS surplus,
                           COALESCE(-SUM(discrepancy) FILTER (WHERE discrepancy < 0), 0) AS shortage
                    FROM inventory_audit_item GROUP BY inventory_audit_id)
                SELECT w.id AS warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,
                       count(*) AS audits,
                       count(*) FILTER (WHERE a.status = 'COMPLETED') AS completed,
                       count(*) FILTER (WHERE a.status NOT IN ('COMPLETED', 'CANCELLED')) AS open,
                       count(*) FILTER (WHERE a.status = 'CANCELLED') AS cancelled,
                       COALESCE(SUM(t.counted), 0) AS counted, COALESCE(SUM(t.discrepant), 0) AS discrepant,
                       COALESCE(SUM(t.surplus) FILTER (WHERE a.status = 'COMPLETED'), 0) AS surplus,
                       COALESCE(SUM(t.shortage) FILTER (WHERE a.status = 'COMPLETED'), 0) AS shortage,
                       MAX(a.completed_at) AS last_completed_at
                FROM inventory_audit a
                JOIN warehouse w ON w.id = a.warehouse_id
                LEFT JOIN items t ON t.inventory_audit_id = a.id
                """ + where(filter, params) + """
                 GROUP BY w.id, w.code, w.name
                ORDER BY w.code""", params,
                (rs, n) -> new WarehouseRow(rs.getLong("warehouse_id"), rs.getString("warehouse_code"),
                        rs.getString("warehouse_name"), rs.getLong("audits"), rs.getLong("completed"),
                        rs.getLong("open"), rs.getLong("cancelled"), rs.getLong("counted"), rs.getLong("discrepant"),
                        rs.getBigDecimal("surplus"), rs.getBigDecimal("shortage"),
                        instant(rs.getTimestamp("last_completed_at"))));
    }

    /** Item figures cover completed audits only: those are the counts that changed stock. */
    public Totals totals(Filter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        Map<String, Long> byStatus = new HashMap<>();
        jdbc.query("SELECT a.status, count(*) AS audits FROM inventory_audit a " + where + " GROUP BY a.status", params,
                rs -> {
                    byStatus.put(rs.getString("status"), rs.getLong("audits"));
                });
        return jdbc.queryForObject("""
                SELECT count(*) FILTER (WHERE i.counted_at IS NOT NULL) AS counted,
                       count(*) FILTER (WHERE i.discrepancy <> 0) AS discrepant,
                       COALESCE(SUM(i.discrepancy) FILTER (WHERE i.discrepancy > 0), 0) AS surplus,
                       COALESCE(-SUM(i.discrepancy) FILTER (WHERE i.discrepancy < 0), 0) AS shortage
                FROM inventory_audit_item i JOIN inventory_audit a ON a.id = i.inventory_audit_id
                """ + where + " AND a.status = 'COMPLETED'", params,
                (rs, n) -> new Totals(byStatus, rs.getLong("counted"), rs.getLong("discrepant"),
                        rs.getBigDecimal("surplus"), rs.getBigDecimal("shortage")));
    }

    private static String where(Filter filter, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("TRUE");
        if (filter.warehouseIds() != null) {
            conditions.add("a.warehouse_id IN (:scopeIds)");
            params.addValue("scopeIds", filter.warehouseIds().isEmpty() ? List.of(-1L) : filter.warehouseIds());
        }
        if (filter.warehouseId() != null) {
            conditions.add("a.warehouse_id = :warehouseId");
            params.addValue("warehouseId", filter.warehouseId());
        }
        if (filter.status() != null) {
            conditions.add("a.status = :status");
            params.addValue("status", filter.status());
        }
        if (filter.from() != null) {
            conditions.add("a.created_at >= :from");
            params.addValue("from", Timestamp.from(filter.from()));
        }
        if (filter.to() != null) {
            conditions.add("a.created_at < :to");
            params.addValue("to", Timestamp.from(filter.to()));
        }
        return "WHERE " + String.join(" AND ", conditions);
    }

    private static String discrepancyOrder(Sort sort) {
        if (sort.isUnsorted()) {
            return "abs(i.discrepancy) DESC, i.id";
        }
        List<String> terms = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = DISCREPANCY_SORT.get(order.getProperty());
            if (column == null) {
                throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY,
                        "Sorting by '%s' is not supported".formatted(order.getProperty()),
                        Map.of("property", order.getProperty(), "allowed", new TreeSet<>(DISCREPANCY_SORT.keySet())));
            }
            terms.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        terms.add("i.id");
        return String.join(", ", terms);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
