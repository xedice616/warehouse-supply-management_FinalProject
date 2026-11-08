package com.anbar.wms.audit.controller;

import com.anbar.wms.audit.dto.InventoryAuditRequests;
import com.anbar.wms.audit.dto.InventoryAuditResponses.AuditResponse;
import com.anbar.wms.audit.dto.InventoryAuditResponses.DiscrepancyRow;
import com.anbar.wms.audit.dto.InventoryAuditResponses.Statistics;
import com.anbar.wms.audit.dto.InventoryAuditResponses.WarehouseStatistics;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.audit.service.InventoryAuditReportService;
import com.anbar.wms.audit.service.InventoryAuditService;
import com.anbar.wms.common.web.Locations;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Inventory audits", description = "Physical counts: DRAFT → IN_PROGRESS → PENDING_APPROVAL → APPROVED → "
        + "COMPLETED. Completion posts the discrepancies as one ADJUSTMENT document. Non-admins work in their warehouses.")
@RestController
@RequestMapping("/api/v1/inventory-audits")
public class InventoryAuditController {

    private static final String COUNTERS = "hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')";

    private final InventoryAuditService audits;
    private final InventoryAuditReportService reports;

    public InventoryAuditController(InventoryAuditService audits, InventoryAuditReportService reports) {
        this.audits = audits;
        this.reports = reports;
    }

    @Operation(summary = "Audit history", description = "auditNumber is a prefix; from/to filter the creation day. "
            + "Sortable by createdAt (default, newest first), auditNumber, status, completedAt.")
    @GetMapping
    public PageResponse<AuditResponse> search(@RequestParam(required = false) String auditNumber,
                                              @RequestParam(required = false) Long warehouseId,
                                              @RequestParam(required = false) AuditStatus status,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @ParameterObject Pageable pageable,
                                              @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.search(user.warehouseScope(), auditNumber, warehouseId, status, from, to, pageable);
    }

    @Operation(summary = "Counted items that differed from the system",
            description = "Sortable by discrepancy (absolute, default largest first), countedAt, auditNumber, sku.")
    @GetMapping("/discrepancies")
    public PageResponse<DiscrepancyRow> discrepancies(@RequestParam(required = false) Long warehouseId,
                                                      @RequestParam(required = false) AuditStatus status,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                      @ParameterObject Pageable pageable,
                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.discrepancies(user.warehouseScope(), warehouseId, status, from, to, pageable);
    }

    @Operation(summary = "Audit statistics per warehouse", description = "Surplus and shortage cover completed audits.")
    @GetMapping("/statistics/by-warehouse")
    public List<WarehouseStatistics> byWarehouse(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.byWarehouse(user.warehouseScope(), from, to);
    }

    @Operation(summary = "Audit statistics", description = "Audits by status; item counts, accuracy, surplus and "
            + "shortage over completed audits.")
    @GetMapping("/statistics")
    public Statistics statistics(@RequestParam(required = false) Long warehouseId,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.statistics(user.warehouseScope(), warehouseId, from, to);
    }

    @GetMapping("/{id:\\d+}")
    public AuditResponse get(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.get(id, user.warehouseScope());
    }

    @Operation(summary = "Create a draft audit",
            description = "fullAudit=true adds every stocked item of the warehouse; otherwise add items yourself.")
    @PreAuthorize(COUNTERS)
    @PostMapping
    public ResponseEntity<AuditResponse> create(@Valid @RequestBody InventoryAuditRequests.Create request,
                                                @AuthenticationPrincipal AuthenticatedUser user) {
        AuditResponse created = audits.create(request, user);
        return ResponseEntity.created(Locations.ofCreated(created.id())).body(created);
    }

    @Operation(summary = "Add items to count",
            description = "A batch-tracked product without batchNumber adds each of its stocked batches.")
    @PreAuthorize(COUNTERS)
    @PostMapping("/{id:\\d+}/items")
    public AuditResponse addItems(@PathVariable long id, @Valid @RequestBody InventoryAuditRequests.AddItems request,
                                  @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.addItems(id, request, user);
    }

    @PreAuthorize(COUNTERS)
    @PatchMapping("/{id:\\d+}/start")
    public AuditResponse start(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.start(id, user);
    }

    @Operation(summary = "Record or correct a count",
            description = "The expected quantity is taken from the system balance at this moment.")
    @PreAuthorize(COUNTERS)
    @PutMapping("/{id:\\d+}/items/{itemId:\\d+}/count")
    public AuditResponse count(@PathVariable long id, @PathVariable long itemId,
                               @Valid @RequestBody InventoryAuditRequests.Count request,
                               @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.count(id, itemId, request, user);
    }

    @Operation(summary = "Submit for approval", description = "Every item must be counted.")
    @PreAuthorize(COUNTERS)
    @PatchMapping("/{id:\\d+}/submit")
    public AuditResponse submit(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.submit(id, user);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id:\\d+}/approve")
    public AuditResponse approve(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.approve(id, user);
    }

    @Operation(summary = "Send back for recounting")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id:\\d+}/reject")
    public AuditResponse reject(@PathVariable long id, @Valid @RequestBody InventoryAuditRequests.Reason request,
                                @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.reject(id, request.reason(), user);
    }

    @Operation(summary = "Complete an approved audit",
            description = "Posts the discrepancies as ADJUSTMENT movements, once. Fails without changes if a shortage "
                    + "exceeds the current stock.")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{id:\\d+}/complete")
    public AuditResponse complete(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.complete(id, user);
    }

    @Operation(summary = "Cancel an audit before approval")
    @PreAuthorize(COUNTERS)
    @PatchMapping("/{id:\\d+}/cancel")
    public AuditResponse cancel(@PathVariable long id, @AuthenticationPrincipal AuthenticatedUser user) {
        return audits.cancel(id, user);
    }
}
