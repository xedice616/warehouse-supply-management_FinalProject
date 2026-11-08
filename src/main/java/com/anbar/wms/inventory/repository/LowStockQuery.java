package com.anbar.wms.inventory.repository;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.inventory.repository.view.StockViews.LowStock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Active products whose stock is below their minimum. Products with no balance row at all count as zero stock,
 * which needs an outer join from product to balance; that, plus the aggregate in the HAVING clause, is why this
 * is plain SQL rather than a derived or JPQL query.
 */
@Repository
public class LowStockQuery {

    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "sku", "sku", "name", "product_name", "shortage", "shortage", "quantity", "quantity");

    private static final String BELOW_MINIMUM = """
            SELECT p.id AS product_id, p.sku, p.name AS product_name, u.code AS unit_code,
                   COALESCE(SUM(b.quantity), 0) AS quantity,
                   p.min_stock_quantity,
                   p.min_stock_quantity - COALESCE(SUM(b.quantity), 0) AS shortage
            FROM product p
            JOIN unit_of_measure u ON u.id = p.uom_id
            LEFT JOIN inventory_balance b
                   ON b.product_id = p.id AND (:allWarehouses OR b.warehouse_id IN (:warehouseIds))
            WHERE p.active AND p.min_stock_quantity > 0
            GROUP BY p.id, p.sku, p.name, u.code, p.min_stock_quantity
            HAVING COALESCE(SUM(b.quantity), 0) < p.min_stock_quantity
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LowStockQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @param warehouseIds ignored when {@code allWarehouses}; must not be empty otherwise */
    public Page<LowStock> find(boolean allWarehouses, Collection<Long> warehouseIds, Pageable pageable) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("allWarehouses", allWarehouses)
                .addValue("warehouseIds", allWarehouses ? List.of(-1L) : warehouseIds);

        Long total = jdbc.queryForObject("SELECT count(*) FROM (" + BELOW_MINIMUM + ") low", params, Long.class);
        if (total == null || total == 0) {
            return Page.empty(pageable);
        }

        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        String sql = "SELECT * FROM (" + BELOW_MINIMUM + ") low ORDER BY " + orderBy(pageable.getSort())
                + " LIMIT :limit OFFSET :offset";
        List<LowStock> rows = jdbc.query(sql, params, (rs, rowNum) -> new LowStock(
                rs.getLong("product_id"),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("unit_code"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("min_stock_quantity"),
                rs.getBigDecimal("shortage")));
        return new PageImpl<>(rows, pageable, total);
    }

    private static String orderBy(Sort sort) {
        List<String> terms = new ArrayList<>();
        for (Sort.Order order : sort) {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                Set<String> allowed = new TreeSet<>(SORT_COLUMNS.keySet());
                throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY,
                        "Sorting by '%s' is not supported".formatted(order.getProperty()),
                        Map.of("property", order.getProperty(), "allowed", allowed));
            }
            terms.add(column + (order.isAscending() ? " ASC" : " DESC"));
        }
        if (terms.isEmpty()) {
            terms.add("shortage DESC");
        }
        terms.add("product_id");
        return String.join(", ", terms);
    }
}
