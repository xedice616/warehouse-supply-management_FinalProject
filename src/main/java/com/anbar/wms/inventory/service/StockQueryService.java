package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.service.ProductService;
import com.anbar.wms.common.repository.SearchPatterns;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.inventory.dto.StockResponses.LowStockResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseProductSearchResponse;
import com.anbar.wms.inventory.dto.StockResponses.ProductStockResponse;
import com.anbar.wms.inventory.dto.StockResponses.ProductWarehouseStockResponse;
import com.anbar.wms.inventory.dto.StockResponses.StockLineResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseProductResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseQuantityResponse;
import com.anbar.wms.inventory.mapper.StockMapper;
import com.anbar.wms.inventory.repository.InventoryBalanceRepository;
import com.anbar.wms.inventory.repository.LowStockQuery;
import com.anbar.wms.inventory.repository.WarehouseProductSearchQuery;
import com.anbar.wms.inventory.repository.view.StockViews.BatchQuantity;
import com.anbar.wms.inventory.repository.view.StockViews.LowStock;
import com.anbar.wms.inventory.repository.view.StockViews.WarehouseProductMatch;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.service.WarehouseService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views of stock on hand. Quantities come from the balance projection of the stock ledger. */
@Service
@Transactional(readOnly = true)
public class StockQueryService {

    private static final SortWhitelist LINE_SORT = SortWhitelist.of(
            Map.of("sku", "p.sku", "name", "p.name", "quantity", "b.quantity", "expiresOn", "bt.expiresOn"),
            Sort.by("p.sku").and(Sort.by("bt.expiresOn")));
    private static final SortWhitelist PRODUCT_SORT = SortWhitelist.of(
            Map.of("sku", "p.sku", "name", "p.name"), Sort.by("p.sku"));

    private final InventoryBalanceRepository balances;
    private final LowStockQuery lowStockQuery;
    private final WarehouseProductSearchQuery warehouseProductSearch;
    private final WarehouseService warehouses;
    private final ProductService products;
    private final StockMapper mapper;

    public StockQueryService(InventoryBalanceRepository balances, LowStockQuery lowStockQuery,
                             WarehouseProductSearchQuery warehouseProductSearch, WarehouseService warehouses,
                             ProductService products, StockMapper mapper) {
        this.balances = balances;
        this.lowStockQuery = lowStockQuery;
        this.warehouseProductSearch = warehouseProductSearch;
        this.warehouses = warehouses;
        this.products = products;
        this.mapper = mapper;
    }

    /** Product search within one warehouse, each row carrying the product's available quantity there. */
    public PageResponse<WarehouseProductSearchResponse> searchWarehouseProducts(long warehouseId, String sku,
            String barcode, String name, Long categoryId, boolean inStockOnly, WarehouseScope scope, Pageable pageable) {
        warehouses.requireVisible(warehouseId, scope);
        WarehouseProductSearchQuery.Filter filter = new WarehouseProductSearchQuery.Filter(warehouseId,
                SearchPatterns.isBlank(sku) ? null : Product.normalizeSku(sku),
                SearchPatterns.isBlank(barcode) ? null : barcode.trim(),
                SearchPatterns.isBlank(name) ? null : SearchPatterns.lowerPrefix(name), categoryId, inStockOnly);
        return PageResponse.from(warehouseProductSearch.search(filter, pageable), StockQueryService::toSearchResponse);
    }

    private static WarehouseProductSearchResponse toSearchResponse(WarehouseProductMatch m) {
        return new WarehouseProductSearchResponse(m.productId(), m.sku(), m.productName(), m.barcode(),
                m.categoryName(), m.unitCode(), m.availableQuantity());
    }

    public PageResponse<StockLineResponse> warehouseStock(long warehouseId, boolean includeZero,
                                                          WarehouseScope scope, Pageable pageable) {
        warehouses.requireVisible(warehouseId, scope);
        return PageResponse.from(balances.findStockLines(warehouseId, includeZero, LINE_SORT.apply(pageable)),
                mapper::toResponse);
    }

    public PageResponse<WarehouseProductResponse> warehouseProducts(long warehouseId, boolean includeZero,
                                                                    WarehouseScope scope, Pageable pageable) {
        warehouses.requireVisible(warehouseId, scope);
        return PageResponse.from(balances.findProductTotals(warehouseId, includeZero, PRODUCT_SORT.apply(pageable)),
                mapper::toResponse);
    }

    public ProductWarehouseStockResponse productInWarehouse(long warehouseId, long productId, WarehouseScope scope) {
        Warehouse warehouse = warehouses.requireVisible(warehouseId, scope);
        Product product = products.require(productId);
        List<BatchQuantity> batches = balances.findBatches(warehouseId, productId);
        BigDecimal total = batches.stream().map(BatchQuantity::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProductWarehouseStockResponse(warehouse.getId(), warehouse.getCode(), product.getId(),
                product.getSku(), product.getName(), product.getUnitOfMeasure().getCode(), total,
                batches.stream().map(mapper::toResponse).toList());
    }

    /** Totals over the warehouses the caller may see; for an administrator, every warehouse. */
    public ProductStockResponse productAcrossWarehouses(long productId, WarehouseScope scope) {
        Product product = products.require(productId);
        List<WarehouseQuantityResponse> perWarehouse = scopeIsEmpty(scope)
                ? List.of()
                : balances.findWarehouseTotals(productId, scope.unrestricted(), scopedIds(scope)).stream()
                        .map(mapper::toResponse)
                        .toList();
        BigDecimal total = perWarehouse.stream()
                .map(WarehouseQuantityResponse::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ProductStockResponse(product.getId(), product.getSku(), product.getName(),
                product.getUnitOfMeasure().getCode(), total, product.getMinStockQuantity(),
                mapper.isBelow(total, product.getMinStockQuantity()), perWarehouse);
    }

    /**
     * Active products below their minimum: in one warehouse when {@code warehouseId} is given, otherwise summed
     * over the caller's warehouses.
     */
    public PageResponse<LowStockResponse> lowStock(Long warehouseId, WarehouseScope scope, Pageable pageable) {
        Pageable page = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), pageable.getSort());
        if (warehouseId != null) {
            warehouses.requireVisible(warehouseId, scope);
            return PageResponse.from(lowStockQuery.find(false, Set.of(warehouseId), page), mapper::toResponse);
        }
        if (scopeIsEmpty(scope)) {
            return PageResponse.from(Page.<LowStock>empty(page), mapper::toResponse);
        }
        return PageResponse.from(lowStockQuery.find(scope.unrestricted(), scopedIds(scope), page), mapper::toResponse);
    }

    private static boolean scopeIsEmpty(WarehouseScope scope) {
        return !scope.unrestricted() && scope.warehouseIds().isEmpty();
    }

    private static Set<Long> scopedIds(WarehouseScope scope) {
        return scope.unrestricted() ? Set.of(-1L) : scope.warehouseIds();
    }
}
