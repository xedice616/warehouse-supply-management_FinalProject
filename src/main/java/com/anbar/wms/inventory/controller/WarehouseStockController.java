package com.anbar.wms.inventory.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.dto.StockResponses.ProductWarehouseStockResponse;
import com.anbar.wms.inventory.dto.StockResponses.StockLineResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseProductResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseProductSearchResponse;
import com.anbar.wms.inventory.service.StockQueryService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stock", description = "Read-only. Stock changes only through stock movements, never through these endpoints.")
@RestController
@RequestMapping("/api/v1/warehouses/{warehouseId}")
public class WarehouseStockController {

    private final StockQueryService service;

    public WarehouseStockController(StockQueryService service) {
        this.service = service;
    }

    @Operation(summary = "Products in a warehouse",
            description = "One row per product with its total quantity. Sortable by sku, name.")
    @GetMapping("/products")
    public PageResponse<WarehouseProductResponse> products(@PathVariable long warehouseId,
                                                           @RequestParam(defaultValue = "false") boolean includeZero,
                                                           @ParameterObject Pageable pageable,
                                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return service.warehouseProducts(warehouseId, includeZero, user.warehouseScope(), pageable);
    }

    @Operation(summary = "Stock lines in a warehouse",
            description = "One row per product and batch. Sortable by sku, name, quantity, expiresOn.")
    @GetMapping("/stock")
    public PageResponse<StockLineResponse> stock(@PathVariable long warehouseId,
                                                 @RequestParam(defaultValue = "false") boolean includeZero,
                                                 @ParameterObject Pageable pageable,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return service.warehouseStock(warehouseId, includeZero, user.warehouseScope(), pageable);
    }

    @Operation(summary = "Search products in a warehouse",
            description = "Lightweight product search limited to one warehouse, each row carrying its available quantity "
                    + "there. sku and barcode are exact, q is a name prefix; inStockOnly hides products with no stock. "
                    + "Sortable by sku, name, availableQuantity.")
    @GetMapping("/products/search")
    public PageResponse<WarehouseProductSearchResponse> searchProducts(@PathVariable long warehouseId,
                                                                       @RequestParam(required = false) String sku,
                                                                       @RequestParam(required = false) String barcode,
                                                                       @RequestParam(required = false) String q,
                                                                       @RequestParam(required = false) Long categoryId,
                                                                       @RequestParam(defaultValue = "false") boolean inStockOnly,
                                                                       @ParameterObject Pageable pageable,
                                                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return service.searchWarehouseProducts(warehouseId, sku, barcode, q, categoryId, inStockOnly,
                user.warehouseScope(), pageable);
    }

    @Operation(summary = "Current stock of one product in a warehouse, with its batches")
    @GetMapping("/stock/{productId}")
    public ProductWarehouseStockResponse productStock(@PathVariable long warehouseId, @PathVariable long productId,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return service.productInWarehouse(warehouseId, productId, user.warehouseScope());
    }
}
