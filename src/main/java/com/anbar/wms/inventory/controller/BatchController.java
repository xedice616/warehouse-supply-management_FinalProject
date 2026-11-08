package com.anbar.wms.inventory.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.dto.BatchResponses.BatchStockResponse;
import com.anbar.wms.inventory.dto.BatchResponses.ExpirationRiskResponse;
import com.anbar.wms.inventory.dto.MovementSearch;
import com.anbar.wms.inventory.dto.StockMovementResponse;
import com.anbar.wms.inventory.service.BatchQueryService;
import com.anbar.wms.inventory.service.StockMovementQueryService;
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

@Tag(name = "Batches", description = "Batch stock per warehouse and expiration reports. Read-only; batches are created by "
        + "receipts and inbound movements, and their quantities change only through stock movements.")
@RestController
@RequestMapping("/api/v1/batches")
public class BatchController {

    private static final String SORTS = " Sortable by expirationDate (default), batchNumber, availableQuantity, sku, createdAt.";

    private final BatchQueryService batches;
    private final StockMovementQueryService history;

    public BatchController(BatchQueryService batches, StockMovementQueryService history) {
        this.batches = batches;
        this.history = history;
    }

    @Operation(summary = "Batch stock", description = "batchNumber is a prefix; empty batches are hidden unless includeEmpty." + SORTS)
    @GetMapping
    public PageResponse<BatchStockResponse> search(@RequestParam(required = false) Long productId,
                                                   @RequestParam(required = false) Long warehouseId,
                                                   @RequestParam(required = false) String batchNumber,
                                                   @RequestParam(defaultValue = "false") boolean includeEmpty,
                                                   @ParameterObject Pageable pageable,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.search(productId, warehouseId, batchNumber, includeEmpty, user.warehouseScope(), pageable);
    }

    @Operation(summary = "One batch in every warehouse holding it")
    @GetMapping("/{id:\\d+}")
    public PageResponse<BatchStockResponse> get(@PathVariable long id, @ParameterObject Pageable pageable,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.get(id, user.warehouseScope(), pageable);
    }

    @GetMapping("/product/{productId}")
    public PageResponse<BatchStockResponse> byProduct(@PathVariable long productId, @ParameterObject Pageable pageable,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.search(productId, null, null, false, user.warehouseScope(), pageable);
    }

    @GetMapping("/warehouse/{warehouseId}")
    public PageResponse<BatchStockResponse> byWarehouse(@PathVariable long warehouseId, @ParameterObject Pageable pageable,
                                                        @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.search(null, warehouseId, null, false, user.warehouseScope(), pageable);
    }

    @Operation(summary = "Expired batches that still hold stock", description = SORTS)
    @GetMapping("/expired")
    public PageResponse<BatchStockResponse> expired(@RequestParam(required = false) Long warehouseId,
                                                    @ParameterObject Pageable pageable,
                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.expired(warehouseId, user.warehouseScope(), pageable);
    }

    @Operation(summary = "Batches expiring within the next N days",
            description = "days: 7, 30 or any value from 0 to 3650 (default 30). Only batches with stock." + SORTS)
    @GetMapping("/expiring")
    public PageResponse<BatchStockResponse> expiring(@RequestParam(defaultValue = "30") int days,
                                                     @RequestParam(required = false) Long warehouseId,
                                                     @ParameterObject Pageable pageable,
                                                     @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.expiring(days, warehouseId, user.warehouseScope(), pageable);
    }

    @Operation(summary = "Products with the highest expiration risk",
            description = "Available quantity already expired, then expiring within the next N days, per product.")
    @GetMapping("/expiration-risk")
    public PageResponse<ExpirationRiskResponse> expirationRisk(@RequestParam(defaultValue = "30") int days,
                                                               @RequestParam(required = false) Long warehouseId,
                                                               @ParameterObject Pageable pageable,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return batches.expirationRisk(days, warehouseId, user.warehouseScope(), pageable);
    }

    @Operation(summary = "Movement history of a batch")
    @GetMapping("/{id:\\d+}/movements")
    public PageResponse<StockMovementResponse> movements(@PathVariable long id, @ParameterObject Pageable pageable,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return history.search(new MovementSearch(null, id, null, null, null, null, null, null),
                user.warehouseScope(), pageable);
    }
}
