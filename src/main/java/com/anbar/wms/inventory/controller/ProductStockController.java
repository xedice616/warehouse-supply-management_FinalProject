package com.anbar.wms.inventory.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.dto.StockResponses.LowStockResponse;
import com.anbar.wms.inventory.dto.StockResponses.ProductStockResponse;
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

@Tag(name = "Stock")
@RestController
@RequestMapping("/api/v1/products")
public class ProductStockController {

    private final StockQueryService service;

    public ProductStockController(StockQueryService service) {
        this.service = service;
    }

    @Operation(summary = "Stock of a product across warehouses",
            description = "Totals over the warehouses the caller may see (all of them for ADMIN).")
    @GetMapping("/{productId}/stock")
    public ProductStockResponse stock(@PathVariable long productId, @AuthenticationPrincipal AuthenticatedUser user) {
        return service.productAcrossWarehouses(productId, user.warehouseScope());
    }

    @Operation(summary = "Active products below their minimum stock",
            description = "Per warehouse when warehouseId is given, otherwise summed over the caller's warehouses. "
                    + "Products with no stock at all count as zero. Sortable by shortage (default), sku, name, quantity.")
    @GetMapping("/low-stock")
    public PageResponse<LowStockResponse> lowStock(@RequestParam(required = false) Long warehouseId,
                                                   @ParameterObject Pageable pageable,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return service.lowStock(warehouseId, user.warehouseScope(), pageable);
    }
}
