package com.anbar.wms.inventory.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.dto.MovementSearch;
import com.anbar.wms.inventory.dto.StockMovementRequests;
import com.anbar.wms.inventory.dto.StockMovementResponse;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.service.StockMovementQueryService;
import com.anbar.wms.inventory.service.StockMovementService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.LocalDate;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Stock movements",
        description = "The only way stock changes. Records are immutable: there is no update or delete endpoint.")
@RestController
@RequestMapping("/api/v1/stock-movements")
public class StockMovementController {

    private static final String OPERATORS = "hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')";

    private final StockMovementService movements;
    private final StockMovementQueryService history;

    public StockMovementController(StockMovementService movements, StockMovementQueryService history) {
        this.movements = movements;
        this.history = history;
    }

    // ------------------------------------------------------------------ postings

    @Operation(summary = "Receive goods into a warehouse")
    @PreAuthorize(OPERATORS)
    @PostMapping("/inbound")
    public ResponseEntity<StockMovementResponse> inbound(@Valid @RequestBody StockMovementRequests.Inbound request,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return created(movements.inbound(request, user));
    }

    @Operation(summary = "Issue goods from a warehouse", description = "422 INSUFFICIENT_STOCK if more is requested than is on hand.")
    @PreAuthorize(OPERATORS)
    @PostMapping("/outbound")
    public ResponseEntity<StockMovementResponse> outbound(@Valid @RequestBody StockMovementRequests.Outbound request,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return created(movements.outbound(request, user));
    }

    @Operation(summary = "Move goods between two warehouses",
            description = "Source decreases and destination increases in one transaction. Managers need access to both.")
    @PreAuthorize(OPERATORS)
    @PostMapping("/transfer")
    public ResponseEntity<StockMovementResponse> transfer(@Valid @RequestBody StockMovementRequests.Transfer request,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return created(movements.transfer(request, user));
    }

    @Operation(summary = "Correct stock up or down (ADMIN only)", description = "A reason is required.")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/adjustment")
    public ResponseEntity<StockMovementResponse> adjustment(@Valid @RequestBody StockMovementRequests.Adjustment request,
                                                            @AuthenticationPrincipal AuthenticatedUser user) {
        return created(movements.adjust(request, user));
    }

    @Operation(summary = "Remove damaged or expired goods", description = "A reason is required. Expired batches are allowed.")
    @PreAuthorize(OPERATORS)
    @PostMapping("/write-off")
    public ResponseEntity<StockMovementResponse> writeOff(@Valid @RequestBody StockMovementRequests.WriteOff request,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return created(movements.writeOff(request, user));
    }

    // ------------------------------------------------------------------ history

    @Operation(summary = "Search movement history",
            description = "Filters combine with AND; from/to are inclusive dates. Sortable by movementDate (default, newest first), quantity, id.")
    @GetMapping
    public PageResponse<StockMovementResponse> search(@RequestParam(required = false) Long productId,
                                                      @RequestParam(required = false) Long batchId,
                                                      @RequestParam(required = false) Long warehouseId,
                                                      @RequestParam(required = false) MovementType type,
                                                      @RequestParam(required = false) Long supplierId,
                                                      @RequestParam(required = false) String referenceNumber,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @ParameterObject Pageable pageable,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return history.search(new MovementSearch(productId, batchId, warehouseId, type, supplierId, referenceNumber, from, to),
                user.warehouseScope(), pageable);
    }

    @GetMapping("/{id:\\d+}")
    public StockMovementResponse get(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return history.get(id, user.warehouseScope());
    }

    @GetMapping("/product/{productId}")
    public PageResponse<StockMovementResponse> byProduct(@PathVariable long productId, @ParameterObject Pageable pageable,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return history.search(new MovementSearch(productId, null, null, null, null, null, null, null),
                user.warehouseScope(), pageable);
    }

    @GetMapping("/warehouse/{warehouseId}")
    public PageResponse<StockMovementResponse> byWarehouse(@PathVariable long warehouseId,
                                                           @ParameterObject Pageable pageable,
                                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return history.search(new MovementSearch(null, null, warehouseId, null, null, null, null, null),
                user.warehouseScope(), pageable);
    }

    @Operation(summary = "Inbound history")
    @GetMapping("/inbound")
    public PageResponse<StockMovementResponse> inboundHistory(@ParameterObject Pageable pageable,
                                                              @AuthenticationPrincipal AuthenticatedUser user) {
        return ofType(MovementType.INBOUND, pageable, user);
    }

    @Operation(summary = "Outbound history")
    @GetMapping("/outbound")
    public PageResponse<StockMovementResponse> outboundHistory(@ParameterObject Pageable pageable,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ofType(MovementType.OUTBOUND, pageable, user);
    }

    @Operation(summary = "Transfer history")
    @GetMapping("/transfer")
    public PageResponse<StockMovementResponse> transferHistory(@ParameterObject Pageable pageable,
                                                               @AuthenticationPrincipal AuthenticatedUser user) {
        return ofType(MovementType.TRANSFER, pageable, user);
    }

    private PageResponse<StockMovementResponse> ofType(MovementType type, Pageable pageable, AuthenticatedUser user) {
        return history.search(new MovementSearch(null, null, null, type, null, null, null, null),
                user.warehouseScope(), pageable);
    }

    private static ResponseEntity<StockMovementResponse> created(StockMovementResponse movement) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/stock-movements/" + movement.id()))
                .body(movement);
    }
}
