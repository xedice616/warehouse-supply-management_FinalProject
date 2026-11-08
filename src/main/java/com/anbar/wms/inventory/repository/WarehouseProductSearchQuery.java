package com.anbar.wms.inventory.repository;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.inventory.repository.view.StockViews.WarehouseProductMatch;
import java.util.ArrayList;
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
 * Product search scoped to one warehouse, returning each product's available quantity there. A left join to the
 * balance keeps products with no stock in the warehouse (unless {@code inStockOnly}); the aggregate is why this is
 * plain SQL. Filters combine with AND and all hit covering indexes.
 */
@Repository
public class WarehouseProductSearchQuery {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "sku", "p.sku", "name", "p.name", "availableQuantity", "available");

    private static final String FROM = """
            FROM product p
            JOIN product_category c ON c.id = p.category_id
            JOIN unit_of_measure u ON u.id = p.uom_id
            LEFT JOIN inventory_balance b ON b.product_id = p.id AND b.warehouse_id = :warehouseId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public WarehouseProductSearchQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Filter(long warehouseId, String sku, String barcode, String namePattern, Long categoryId,
                         boolean inStockOnly) {
    }

    public Page<WarehouseProductMatch> search(Filter filter, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("warehouseId", filter.warehouseId())
                .addValue("sku", filter.sku())
                .addValue("barcode", filter.barcode())
                .addValue("namePattern", filter.namePattern())
                .addValue("categoryId", filter.categoryId());
        String where = """
                WHERE (CAST(:sku AS text) IS NULL OR p.sku = :sku)
                  AND (CAST(:barcode AS text) IS NULL OR p.barcode = :barcode)
                  AND (CAST(:namePattern AS text) IS NULL OR lower(p.name) LIKE :namePattern ESCAPE '\\')
                  AND (CAST(:categoryId AS bigint) IS NULL OR c.id = :categoryId)
                """ + (filter.inStockOnly()
                ? " AND EXISTS (SELECT 1 FROM inventory_balance s WHERE s.product_id = p.id"
                        + " AND s.warehouse_id = :warehouseId AND s.quantity > 0)"
                : "");

        Long total = jdbc.queryForObject("SELECT count(DISTINCT p.id) " + FROM + where, params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = """
                SELECT p.id, p.sku, p.name AS product_name, p.barcode, c.name AS category_name, u.code AS unit_code,
                       COALESCE(SUM(b.quantity), 0) AS available
                """ + FROM + where
                + " GROUP BY p.id, p.sku, p.name, p.barcode, c.name, u.code ORDER BY " + orderBy(pageable.getSort())
                + " LIMIT :limit OFFSET :offset";
        List<WarehouseProductMatch> rows = jdbc.query(sql, params, (rs, n) -> new WarehouseProductMatch(
                rs.getLong("id"), rs.getString("sku"), rs.getString("product_name"), rs.getString("barcode"),
                rs.getString("category_name"), rs.getString("unit_code"), rs.getBigDecimal("available")));
        return new PageImpl<>(rows, pageable, total);
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
            terms.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        if (terms.isEmpty()) {
            terms.add("p.sku ASC");
        }
        terms.add("p.id");
        return String.join(", ", terms);
    }
}
