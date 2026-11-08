package com.anbar.wms.inventory.repository;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
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
 * Batch stock per warehouse: the batch rows of the balance projection joined to their lot, product and warehouse.
 * Available quantity is the balance; initial quantity is everything that ever entered that warehouse for the batch.
 */
@Repository
public class BatchStockQuery {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "expirationDate", "s.expires_on", "batchNumber", "s.batch_number", "availableQuantity", "b.quantity",
            "sku", "p.sku", "createdAt", "s.created_at");

    private static final String FROM = """
            FROM inventory_balance b
            JOIN stock_batch s ON s.id = b.batch_id
            JOIN product p ON p.id = b.product_id
            JOIN unit_of_measure u ON u.id = p.uom_id
            JOIN warehouse w ON w.id = b.warehouse_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BatchStockQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param warehouseIds    null for every warehouse
     * @param expiresFrom     inclusive lower bound on the expiration date, or null
     * @param expiresBefore   exclusive upper bound on the expiration date, or null
     */
    public record Filter(Collection<Long> warehouseIds, Long batchId, Long productId, Long warehouseId,
                         String batchNumberPrefix, boolean includeEmpty, LocalDate expiresFrom, LocalDate expiresBefore) {
    }

    public record Row(long batchId, String batchNumber, long productId, String sku, String productName,
                      String unitCode, long warehouseId, String warehouseCode, String warehouseName,
                      BigDecimal initialQuantity, BigDecimal availableQuantity, LocalDate productionDate,
                      LocalDate expirationDate, Long purchaseOrderItemId, BigDecimal unitCost,
                      Instant createdAt, Instant updatedAt) {
    }

    public record ProductRisk(long productId, String sku, String productName, String unitCode, int batchCount,
                              BigDecimal expiredQuantity, BigDecimal expiringQuantity, LocalDate earliestExpiration) {
    }

    public Page<Row> find(Filter filter, Pageable pageable, Sort defaultSort) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        Long total = jdbc.queryForObject("SELECT count(*) " + FROM + where, params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = """
                SELECT s.id AS batch_id, s.batch_number, p.id AS product_id, p.sku, p.name AS product_name,
                       u.code AS unit_code, w.id AS warehouse_id, w.code AS warehouse_code, w.name AS warehouse_name,
                       b.total_received, b.quantity, s.manufactured_on, s.expires_on, s.purchase_order_line_id,
                       s.unit_cost, s.created_at, b.updated_at
                """ + FROM + where + " ORDER BY " + orderBy(pageable.getSort().isSorted() ? pageable.getSort() : defaultSort)
                + " LIMIT :limit OFFSET :offset";
        return new PageImpl<>(jdbc.query(sql, params, BatchStockQuery::row), pageable, total);
    }

    /** Products ranked by expired, then soon-expiring, available quantity. */
    public Page<ProductRisk> risk(Collection<Long> warehouseIds, Long warehouseId, LocalDate today, LocalDate horizon,
                                  Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("today", Date.valueOf(today), Types.DATE)
                .addValue("horizon", Date.valueOf(horizon), Types.DATE);
        String where = where(new Filter(warehouseIds, null, null, warehouseId, null, false, null, horizon.plusDays(1)),
                params) + " AND s.expires_on IS NOT NULL";
        String grouped = """
                SELECT p.id AS product_id, p.sku, p.name AS product_name, u.code AS unit_code,
                       count(DISTINCT s.id) AS batch_count,
                       COALESCE(SUM(b.quantity) FILTER (WHERE s.expires_on < :today), 0) AS expired_quantity,
                       COALESCE(SUM(b.quantity) FILTER (WHERE s.expires_on >= :today), 0) AS expiring_quantity,
                       MIN(s.expires_on) AS earliest_expiration
                """ + FROM + where + " GROUP BY p.id, p.sku, p.name, u.code";
        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + grouped + ") r", params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<ProductRisk> rows = jdbc.query("SELECT * FROM (" + grouped + ") r "
                        + "ORDER BY expired_quantity DESC, expiring_quantity DESC, earliest_expiration, product_id "
                        + "LIMIT :limit OFFSET :offset", params,
                (rs, n) -> new ProductRisk(rs.getLong("product_id"), rs.getString("sku"), rs.getString("product_name"),
                        rs.getString("unit_code"), rs.getInt("batch_count"), rs.getBigDecimal("expired_quantity"),
                        rs.getBigDecimal("expiring_quantity"), rs.getObject("earliest_expiration", LocalDate.class)));
        return new PageImpl<>(rows, pageable, total);
    }

    private static String where(Filter filter, MapSqlParameterSource params) {
        List<String> conditions = new ArrayList<>();
        conditions.add("b.batch_id IS NOT NULL");
        if (filter.warehouseIds() != null) {
            conditions.add(filter.warehouseIds().isEmpty() ? "FALSE" : "b.warehouse_id IN (:scopeIds)");
            params.addValue("scopeIds", filter.warehouseIds().isEmpty() ? List.of(-1L) : filter.warehouseIds());
        }
        if (filter.batchId() != null) {
            conditions.add("s.id = :batchId");
            params.addValue("batchId", filter.batchId());
        }
        if (filter.productId() != null) {
            conditions.add("b.product_id = :productId");
            params.addValue("productId", filter.productId());
        }
        if (filter.warehouseId() != null) {
            conditions.add("b.warehouse_id = :warehouseId");
            params.addValue("warehouseId", filter.warehouseId());
        }
        if (filter.batchNumberPrefix() != null && !filter.batchNumberPrefix().isBlank()) {
            conditions.add("lower(s.batch_number) LIKE :batchPrefix ESCAPE '\\'");
            params.addValue("batchPrefix", filter.batchNumberPrefix().trim().toLowerCase(Locale.ROOT)
                    .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (!filter.includeEmpty()) {
            conditions.add("b.quantity > 0");
        }
        if (filter.expiresFrom() != null) {
            conditions.add("s.expires_on >= :expiresFrom");
            params.addValue("expiresFrom", Date.valueOf(filter.expiresFrom()), Types.DATE);
        }
        if (filter.expiresBefore() != null) {
            conditions.add("s.expires_on < :expiresBefore");
            params.addValue("expiresBefore", Date.valueOf(filter.expiresBefore()), Types.DATE);
        }
        return "WHERE " + String.join(" AND ", conditions);
    }

    private static String orderBy(Sort sort) {
        List<String> terms = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY,
                        "Sorting by '%s' is not supported".formatted(order.getProperty()),
                        Map.of("property", order.getProperty(), "allowed", new TreeSet<>(SORT_COLUMNS.keySet())));
            }
            terms.add(column + (order.isAscending() ? " ASC NULLS LAST" : " DESC NULLS LAST"));
        }
        terms.add("s.id");
        terms.add("w.id");
        return String.join(", ", terms);
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        long poLine = rs.getLong("purchase_order_line_id");
        return new Row(rs.getLong("batch_id"), rs.getString("batch_number"), rs.getLong("product_id"),
                rs.getString("sku"), rs.getString("product_name"), rs.getString("unit_code"),
                rs.getLong("warehouse_id"), rs.getString("warehouse_code"), rs.getString("warehouse_name"),
                rs.getBigDecimal("total_received"), rs.getBigDecimal("quantity"),
                rs.getObject("manufactured_on", LocalDate.class), rs.getObject("expires_on", LocalDate.class),
                poLine == 0 ? null : poLine, rs.getBigDecimal("unit_cost"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
