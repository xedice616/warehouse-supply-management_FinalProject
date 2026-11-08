package com.anbar.wms.reporting.repository;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.reporting.model.ReportParams.Granularity;
import com.anbar.wms.reporting.model.ReportParams.ValuationGrouping;
import com.anbar.wms.reporting.model.ReportResponses.InOutPeriod;
import com.anbar.wms.reporting.model.ReportResponses.InOutStatistics;
import com.anbar.wms.reporting.model.ReportResponses.MovementReportRow;
import com.anbar.wms.reporting.model.ReportResponses.SupplierPerformanceRow;
import com.anbar.wms.reporting.model.ReportResponses.TopProductRow;
import com.anbar.wms.reporting.model.ReportResponses.ValuationRow;
import com.anbar.wms.reporting.model.ReportResponses.WarehousePerformanceRow;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
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

/**
 * Read-only analytical queries over the existing tables. Every query aggregates in the database and respects the
 * caller's warehouse scope. Movement direction is defined by the ledger: a quantity is inbound when it enters a
 * scoped warehouse (destination) and outbound when it leaves one (source), independent of the movement type.
 */
@Repository
public class ReportQueries {

    /** @param warehouseIds a non-empty placeholder (e.g. [-1]) when {@code allWarehouses} */
    public record Scope(boolean allWarehouses, Collection<Long> warehouseIds) {
    }

    public record MovementFilter(Scope scope, Long productId, Long warehouseId, String movementType,
                                 Long performedByUserId, Instant from, Instant to) {
    }

    private static final Map<String, String> MOVEMENT_SORT = Map.of(
            "movementDate", "m.performed_at", "quantity", "m.quantity");
    private static final Map<String, String> TOP_SORT = Map.of(
            "totalVolume", "total_volume", "inboundQuantity", "inbound", "outboundQuantity", "outbound",
            "movementCount", "movement_count", "sku", "p.sku");
    private static final Map<String, String> VALUATION_SORT = Map.of(
            "totalValue", "total_value", "totalQuantity", "total_quantity", "label", "label");

    private final NamedParameterJdbcTemplate jdbc;

    public ReportQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ Report 1: stock movements

    public Page<MovementReportRow> movements(MovementFilter filter, Pageable pageable) {
        MapSqlParameterSource params = scopeParams(filter.scope())
                .addValue("productId", filter.productId())
                .addValue("warehouseId", filter.warehouseId())
                .addValue("movementType", filter.movementType())
                .addValue("performedBy", filter.performedByUserId())
                .addValue("from", timestamp(filter.from()))
                .addValue("to", timestamp(filter.to()));
        String from = """
                FROM stock_movement m
                JOIN stock_transaction t ON t.id = m.transaction_id
                JOIN product p ON p.id = m.product_id
                JOIN unit_of_measure u ON u.id = p.uom_id
                JOIN app_user au ON au.id = m.performed_by
                LEFT JOIN stock_batch sb ON sb.id = m.batch_id
                LEFT JOIN warehouse ws ON ws.id = m.source_warehouse_id
                LEFT JOIN warehouse wd ON wd.id = m.destination_warehouse_id
                WHERE (:allWh OR m.source_warehouse_id IN (:whIds) OR m.destination_warehouse_id IN (:whIds))
                  AND (CAST(:productId AS bigint) IS NULL OR m.product_id = :productId)
                  AND (CAST(:warehouseId AS bigint) IS NULL OR m.source_warehouse_id = :warehouseId OR m.destination_warehouse_id = :warehouseId)
                  AND (CAST(:movementType AS text) IS NULL OR m.movement_type = :movementType)
                  AND (CAST(:performedBy AS bigint) IS NULL OR m.performed_by = :performedBy)
                  AND (CAST(:from AS timestamptz) IS NULL OR m.performed_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR m.performed_at < :to)
                """;
        Long total = jdbc.queryForObject("SELECT count(*) " + from, params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = """
                SELECT m.id, t.transaction_number, m.movement_type, m.performed_at,
                       p.id AS product_id, p.sku, p.name AS product_name, u.code AS unit_code, sb.batch_number,
                       m.quantity, ws.code AS source_code, wd.code AS destination_code,
                       m.performed_by, au.username, t.reference_number
                """ + from + " ORDER BY " + orderBy(pageable.getSort(), MOVEMENT_SORT, "m.performed_at DESC")
                + ", m.id DESC LIMIT :limit OFFSET :offset";
        List<MovementReportRow> rows = jdbc.query(sql, params, (rs, n) -> new MovementReportRow(
                rs.getLong("id"), rs.getString("transaction_number"), rs.getString("movement_type"),
                rs.getTimestamp("performed_at").toInstant(), rs.getLong("product_id"), rs.getString("sku"),
                rs.getString("product_name"), rs.getString("unit_code"), rs.getString("batch_number"),
                rs.getBigDecimal("quantity"), rs.getString("source_code"), rs.getString("destination_code"),
                rs.getObject("performed_by", Long.class), rs.getString("username"), rs.getString("reference_number")));
        return new PageImpl<>(rows, pageable, total);
    }

    // ------------------------------------------------------------------ Report 2: top moved products

    public Page<TopProductRow> topProducts(Scope scope, Instant from, Instant to, Pageable pageable) {
        MapSqlParameterSource params = scopeParams(scope)
                .addValue("from", timestamp(from)).addValue("to", timestamp(to));
        String grouped = """
                SELECT p.id AS product_id, p.sku, p.name AS product_name, u.code AS unit_code,
                       COALESCE(SUM(m.quantity) FILTER (
                           WHERE m.destination_warehouse_id IS NOT NULL
                             AND (:allWh OR m.destination_warehouse_id IN (:whIds))), 0) AS inbound,
                       COALESCE(SUM(m.quantity) FILTER (
                           WHERE m.source_warehouse_id IS NOT NULL
                             AND (:allWh OR m.source_warehouse_id IN (:whIds))), 0) AS outbound,
                       count(*) AS movement_count
                FROM stock_movement m
                JOIN product p ON p.id = m.product_id
                JOIN unit_of_measure u ON u.id = p.uom_id
                WHERE (:allWh OR m.source_warehouse_id IN (:whIds) OR m.destination_warehouse_id IN (:whIds))
                  AND (CAST(:from AS timestamptz) IS NULL OR m.performed_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR m.performed_at < :to)
                GROUP BY p.id, p.sku, p.name, u.code
                """;
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + grouped + ") g", params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = "SELECT *, inbound + outbound AS total_volume FROM (" + grouped + ") g ORDER BY "
                + orderBy(pageable.getSort(), TOP_SORT, "total_volume DESC") + ", product_id LIMIT :limit OFFSET :offset";
        List<TopProductRow> rows = jdbc.query(sql, params, (rs, n) -> new TopProductRow(
                rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("unit_code"),
                rs.getBigDecimal("inbound"), rs.getBigDecimal("outbound"), rs.getBigDecimal("total_volume"),
                rs.getLong("movement_count")));
        return new PageImpl<>(rows, pageable, total);
    }

    // ------------------------------------------------------------------ Report 3: inbound vs outbound

    public InOutStatistics inOutStatistics(Scope scope, Long productId, Instant from, Instant to,
                                           Granularity granularity, String zoneId) {
        MapSqlParameterSource params = scopeParams(scope)
                .addValue("productId", productId).addValue("from", timestamp(from)).addValue("to", timestamp(to));
        String where = """
                FROM stock_movement m
                WHERE (:allWh OR m.source_warehouse_id IN (:whIds) OR m.destination_warehouse_id IN (:whIds))
                  AND (CAST(:productId AS bigint) IS NULL OR m.product_id = :productId)
                  AND (CAST(:from AS timestamptz) IS NULL OR m.performed_at >= :from)
                  AND (CAST(:to AS timestamptz) IS NULL OR m.performed_at < :to)
                """;
        String inbound = "COALESCE(SUM(m.quantity) FILTER (WHERE m.destination_warehouse_id IS NOT NULL"
                + " AND (:allWh OR m.destination_warehouse_id IN (:whIds))), 0)";
        String outbound = "COALESCE(SUM(m.quantity) FILTER (WHERE m.source_warehouse_id IS NOT NULL"
                + " AND (:allWh OR m.source_warehouse_id IN (:whIds))), 0)";

        InOutStatistics totals = jdbc.queryForObject(
                "SELECT " + inbound + " AS inbound, " + outbound + " AS outbound, count(*) AS c " + where, params,
                (rs, n) -> new InOutStatistics(rs.getBigDecimal("inbound"), rs.getBigDecimal("outbound"),
                        rs.getBigDecimal("inbound").subtract(rs.getBigDecimal("outbound")), rs.getLong("c"), List.of()));

        params.addValue("unit", granularity.sqlUnit()).addValue("tz", zoneId);
        // Bucket by calendar day/week/month in the business time zone, not the database session zone.
        List<InOutPeriod> breakdown = jdbc.query(
                "SELECT CAST(date_trunc(:unit, m.performed_at AT TIME ZONE :tz) AS date) AS bucket, "
                        + inbound + " AS inbound, " + outbound + " AS outbound, count(*) AS c " + where
                        + " GROUP BY bucket ORDER BY bucket", params,
                (rs, n) -> {
                    BigDecimal in = rs.getBigDecimal("inbound");
                    BigDecimal out = rs.getBigDecimal("outbound");
                    return new InOutPeriod(rs.getObject("bucket", LocalDate.class), in, out, in.subtract(out),
                            rs.getLong("c"));
                });
        return new InOutStatistics(totals.totalInbound(), totals.totalOutbound(), totals.netMovement(),
                totals.movementCount(), breakdown);
    }

    // ------------------------------------------------------------------ Report 4: inventory valuation

    public Page<ValuationRow> valuation(Scope scope, ValuationGrouping grouping, Long warehouseId, Pageable pageable) {
        MapSqlParameterSource params = scopeParams(scope).addValue("warehouseId", warehouseId);
        String value = "b.quantity * COALESCE(sb.unit_cost, p.purchase_price)";
        String where = """
                FROM inventory_balance b
                JOIN product p ON p.id = b.product_id
                JOIN warehouse w ON w.id = b.warehouse_id
                JOIN product_category c ON c.id = p.category_id
                LEFT JOIN stock_batch sb ON sb.id = b.batch_id
                WHERE b.quantity > 0
                  AND (:allWh OR b.warehouse_id IN (:whIds))
                  AND (CAST(:warehouseId AS bigint) IS NULL OR b.warehouse_id = :warehouseId)
                """;
        String key = switch (grouping) {
            case PRODUCT -> "p.id";
            case WAREHOUSE -> "w.id";
            case CATEGORY -> "c.id";
            case TOTAL -> "'all'";
        };
        String label = switch (grouping) {
            case PRODUCT -> "p.sku || ' - ' || p.name";
            case WAREHOUSE -> "w.code || ' - ' || w.name";
            case CATEGORY -> "c.name";
            case TOTAL -> "'All inventory'";
        };
        String grouped = "SELECT " + key + " AS key, max(" + label + ") AS label, SUM(b.quantity) AS total_quantity, "
                + "SUM(" + value + ") AS total_value " + where
                + (grouping == ValuationGrouping.TOTAL ? "" : " GROUP BY " + key);

        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + grouped + ") g", params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = "SELECT * FROM (" + grouped + ") g ORDER BY "
                + orderBy(pageable.getSort(), VALUATION_SORT, "total_value DESC") + ", key LIMIT :limit OFFSET :offset";
        List<ValuationRow> rows = jdbc.query(sql, params, (rs, n) -> new ValuationRow(
                rs.getString("key"), rs.getString("label"), rs.getBigDecimal("total_quantity"),
                rs.getBigDecimal("total_value")));
        return new PageImpl<>(rows, pageable, total);
    }

    // ------------------------------------------------------------------ Report 5: warehouse performance

    public Page<WarehousePerformanceRow> warehousePerformance(Scope scope, Instant from, Instant to, Pageable pageable) {
        MapSqlParameterSource params = scopeParams(scope)
                .addValue("from", timestamp(from)).addValue("to", timestamp(to));
        String grouped = """
                SELECT w.id, w.code, w.name,
                       COALESCE(snap.products, 0) AS products,
                       COALESCE(snap.quantity, 0) AS quantity,
                       COALESCE(snap.value, 0) AS value,
                       COALESCE(act.inbound, 0) AS inbound,
                       COALESCE(act.outbound, 0) AS outbound,
                       COALESCE(act.movements, 0) AS movements
                FROM warehouse w
                LEFT JOIN (
                    SELECT b.warehouse_id,
                           count(DISTINCT b.product_id) FILTER (WHERE b.quantity > 0) AS products,
                           SUM(b.quantity) AS quantity,
                           SUM(b.quantity * COALESCE(sb.unit_cost, p.purchase_price)) AS value
                    FROM inventory_balance b
                    JOIN product p ON p.id = b.product_id
                    LEFT JOIN stock_batch sb ON sb.id = b.batch_id
                    GROUP BY b.warehouse_id) snap ON snap.warehouse_id = w.id
                LEFT JOIN (
                    SELECT wid AS warehouse_id,
                           SUM(quantity) FILTER (WHERE direction = 'IN') AS inbound,
                           SUM(quantity) FILTER (WHERE direction = 'OUT') AS outbound,
                           count(*) AS movements
                    FROM (
                        SELECT destination_warehouse_id AS wid, quantity, 'IN' AS direction FROM stock_movement
                        WHERE destination_warehouse_id IS NOT NULL
                          AND (CAST(:from AS timestamptz) IS NULL OR performed_at >= :from)
                          AND (CAST(:to AS timestamptz) IS NULL OR performed_at < :to)
                        UNION ALL
                        SELECT source_warehouse_id AS wid, quantity, 'OUT' AS direction FROM stock_movement
                        WHERE source_warehouse_id IS NOT NULL
                          AND (CAST(:from AS timestamptz) IS NULL OR performed_at >= :from)
                          AND (CAST(:to AS timestamptz) IS NULL OR performed_at < :to)) legs
                    GROUP BY wid) act ON act.warehouse_id = w.id
                WHERE (:allWh OR w.id IN (:whIds))
                """;
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + grouped + ") g", params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        BigDecimal grandValue = jdbc.queryForObject(
                "SELECT COALESCE(SUM(value), 0) FROM (" + grouped + ") g", params, BigDecimal.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = "SELECT * FROM (" + grouped + ") g ORDER BY value DESC, id LIMIT :limit OFFSET :offset";
        BigDecimal denominator = grandValue == null || grandValue.signum() == 0 ? null : grandValue;
        List<WarehousePerformanceRow> rows = jdbc.query(sql, params, (rs, n) -> {
            BigDecimal value = rs.getBigDecimal("value");
            BigDecimal percent = denominator == null ? BigDecimal.ZERO
                    : value.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, java.math.RoundingMode.HALF_UP);
            return new WarehousePerformanceRow(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                    rs.getLong("products"), rs.getBigDecimal("quantity"), rs.getBigDecimal("value"),
                    rs.getBigDecimal("inbound"), rs.getBigDecimal("outbound"), rs.getLong("movements"), percent);
        });
        return new PageImpl<>(rows, pageable, total);
    }

    // ------------------------------------------------------------------ Report 6: supplier performance

    public Page<SupplierPerformanceRow> supplierPerformance(Scope scope, LocalDate from, LocalDate to, Pageable pageable) {
        MapSqlParameterSource params = scopeParams(scope)
                .addValue("from", from).addValue("to", to);
        String grouped = """
                SELECT s.id, s.code, s.name AS company_name,
                       count(*) FILTER (WHERE po.status <> 'CANCELLED') AS total_orders,
                       COALESCE(SUM(line.ordered) FILTER (WHERE po.status <> 'CANCELLED'), 0) AS purchased_quantity,
                       COALESCE(SUM(line.amount) FILTER (WHERE po.status <> 'CANCELLED'), 0) AS purchase_value,
                       count(*) FILTER (WHERE po.status = 'RECEIVED') AS completed_orders,
                       count(*) FILTER (WHERE po.status = 'PARTIALLY_RECEIVED') AS partial_orders,
                       count(*) FILTER (WHERE po.status IN ('APPROVED', 'PARTIALLY_RECEIVED')) AS outstanding_orders
                FROM purchase_order po
                JOIN supplier s ON s.id = po.supplier_id
                LEFT JOIN (
                    SELECT purchase_order_id, SUM(ordered_quantity) AS ordered,
                           SUM(ordered_quantity * unit_price) AS amount
                    FROM purchase_order_line GROUP BY purchase_order_id) line ON line.purchase_order_id = po.id
                WHERE (:allWh OR po.warehouse_id IN (:whIds))
                  AND (CAST(:from AS date) IS NULL OR po.order_date >= :from)
                  AND (CAST(:to AS date) IS NULL OR po.order_date <= :to)
                GROUP BY s.id, s.code, s.name
                """;
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + grouped + ") g", params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = "SELECT * FROM (" + grouped + ") g ORDER BY purchase_value DESC, id LIMIT :limit OFFSET :offset";
        List<SupplierPerformanceRow> rows = jdbc.query(sql, params, (rs, n) -> new SupplierPerformanceRow(
                rs.getLong("id"), rs.getString("code"), rs.getString("company_name"), rs.getLong("total_orders"),
                rs.getBigDecimal("purchased_quantity"), rs.getBigDecimal("purchase_value"),
                rs.getLong("completed_orders"), rs.getLong("partial_orders"), rs.getLong("outstanding_orders")));
        return new PageImpl<>(rows, pageable, total);
    }

    // ------------------------------------------------------------------ helpers

    private static MapSqlParameterSource scopeParams(Scope scope) {
        return new MapSqlParameterSource()
                .addValue("allWh", scope.allWarehouses())
                .addValue("whIds", scope.warehouseIds());
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static String orderBy(Sort sort, Map<String, String> columns, String fallback) {
        if (sort.isUnsorted()) {
            return fallback;
        }
        List<String> terms = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = columns.get(order.getProperty());
            if (column == null) {
                throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY,
                        "Sorting by '%s' is not supported".formatted(order.getProperty()),
                        Map.of("property", order.getProperty(), "allowed", new TreeSet<>(columns.keySet())));
            }
            terms.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        return String.join(", ", terms);
    }
}
