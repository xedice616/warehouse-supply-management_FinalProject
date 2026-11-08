package com.anbar.wms.purchasing.controller;

import com.anbar.wms.common.web.Locations;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.purchasing.dto.PurchaseOrderRequests;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.PurchaseOrderResponse;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.ReceiptResponse;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.SummaryRow;
import com.anbar.wms.purchasing.model.PurchaseOrderStatus;
import com.anbar.wms.purchasing.service.PurchaseOrderService;
import com.anbar.wms.purchasing.service.PurchaseOrderService.SummaryGrouping;
import com.anbar.wms.purchasing.service.PurchaseReceivingService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Purchase orders", description = "Create, approve and receive orders. Non-admins see orders of their warehouses.")
@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService orders;
    private final PurchaseReceivingService receiving;

    public PurchaseOrderController(PurchaseOrderService orders, PurchaseReceivingService receiving) {
        this.orders = orders;
        this.receiving = receiving;
    }

    @Operation(summary = "List and search orders",
            description = "orderNumber is a prefix; outstanding=true means APPROVED or PARTIALLY_RECEIVED; from/to filter the "
                    + "order date. Sortable by orderNumber, orderDate (default, newest first), expectedDeliveryDate, status, createdAt.")
    @GetMapping
    public PageResponse<PurchaseOrderResponse> search(@RequestParam(required = false) String orderNumber,
                                                      @RequestParam(required = false) Long supplierId,
                                                      @RequestParam(required = false) Long warehouseId,
                                                      @RequestParam(required = false) PurchaseOrderStatus status,
                                                      @RequestParam(defaultValue = "false") boolean outstanding,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @ParameterObject Pageable pageable,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return orders.search(user.warehouseScope(), orderNumber, supplierId, warehouseId, status, outstanding, from, to,
                pageable);
    }

    @Operation(summary = "Purchase summary report",
            description = "Order count and ordered value grouped by SUPPLIER, WAREHOUSE or STATUS. Sortable by label, orderCount, totalAmount.")
    @GetMapping("/summary")
    public PageResponse<SummaryRow> summary(@RequestParam SummaryGrouping groupBy,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @ParameterObject Pageable pageable,
                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return orders.summary(groupBy, user.warehouseScope(), from, to, pageable);
    }

    @GetMapping("/{id}")
    public PurchaseOrderResponse get(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return orders.get(id, user.warehouseScope());
    }

    @Operation(summary = "Create a draft order", description = "The order number and total are computed by the server.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<PurchaseOrderResponse> create(@Valid @RequestBody PurchaseOrderRequests.Create request) {
        PurchaseOrderResponse created = orders.create(request);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @Operation(summary = "Edit a draft order", description = "Replaces the header and all items. Only DRAFT orders.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}")
    public PurchaseOrderResponse update(@PathVariable long id, @Valid @RequestBody PurchaseOrderRequests.Update request) {
        return orders.update(id, request);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/submit")
    public PurchaseOrderResponse submit(@PathVariable long id) {
        return orders.submit(id);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/approve")
    public PurchaseOrderResponse approve(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return orders.approve(id, user.id());
    }

    @Operation(summary = "Send a submitted order back to DRAFT")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/reject")
    public PurchaseOrderResponse reject(@PathVariable long id, @Valid @RequestBody PurchaseOrderRequests.Reason request) {
        return orders.reject(id, request.reason());
    }

    @Operation(summary = "Cancel an order", description = "Possible until fully received; goods already received stay in stock.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id}/cancel")
    public PurchaseOrderResponse cancel(@PathVariable long id, @Valid @RequestBody PurchaseOrderRequests.Reason request) {
        return orders.cancel(id, request.reason());
    }

    @Operation(summary = "Receive goods (full or partial)",
            description = "Posts one INBOUND stock movement per item. Send a unique Idempotency-Key per receipt: repeating a "
                    + "request with the same key returns the original receipt (200, replayed=true) without receiving again.")
    @PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
    @PostMapping("/{id}/receipts")
    public ResponseEntity<ReceiptResponse> receive(@PathVariable long id,
                                                   @Parameter(description = "Unique per receipt, e.g. a UUID")
                                                   @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                                   @Valid @RequestBody PurchaseOrderRequests.Receipt request,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        ReceiptResponse receipt = receiving.receive(id, idempotencyKey, request, user);
        return ResponseEntity.status(receipt.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(receipt);
    }
}
