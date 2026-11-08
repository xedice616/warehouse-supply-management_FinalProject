package com.anbar.wms.reporting.controller;

import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.reporting.model.ReportResponses.InOutStatistics;
import com.anbar.wms.reporting.model.ReportResponses.MovementReportRow;
import com.anbar.wms.reporting.model.ReportResponses.SupplierPerformanceRow;
import com.anbar.wms.reporting.model.ReportResponses.TopProductRow;
import com.anbar.wms.reporting.model.ReportResponses.ValuationRow;
import com.anbar.wms.reporting.model.ReportResponses.WarehousePerformanceRow;
import com.anbar.wms.reporting.model.ReportParams.Granularity;
import com.anbar.wms.reporting.model.ReportParams.ValuationGrouping;
import com.anbar.wms.reporting.service.ReportService;
import com.anbar.wms.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Reports", description = "Analytical reports aggregated in the database. Every report is limited to the "
        + "warehouses the caller may access. ADMIN and WAREHOUSE_MANAGER only.")
@RestController
@RequestMapping("/api/v1/reports")
@PreAuthorize("hasAnyRole('ADMIN', 'WAREHOUSE_MANAGER')")
public class ReportController {

    private final ReportService reports;

    public ReportController(ReportService reports) {
        this.reports = reports;
    }

    @Operation(summary = "Stock movement report",
            description = "Filter by date range, product, warehouse, movement type and responsible user. "
                    + "Sortable by movementDate (default, newest first), quantity.")
    @GetMapping("/stock-movements")
    public PageResponse<MovementReportRow> stockMovements(@RequestParam(required = false) Long productId,
                                                          @RequestParam(required = false) Long warehouseId,
                                                          @RequestParam(required = false) MovementType type,
                                                          @RequestParam(required = false) Long performedByUserId,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                          @ParameterObject Pageable pageable,
                                                          @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.stockMovements(user.warehouseScope(), productId, warehouseId, type, performedByUserId, from, to, pageable);
    }

    @Operation(summary = "Top moved products",
            description = "Products ranked by movement volume over the period, inbound and outbound shown separately. "
                    + "Sortable by totalVolume (default), inboundQuantity, outboundQuantity, movementCount, sku.")
    @GetMapping("/top-products")
    public PageResponse<TopProductRow> topProducts(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @ParameterObject Pageable pageable,
                                                   @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.topProducts(user.warehouseScope(), from, to, pageable);
    }

    @Operation(summary = "Inbound vs outbound statistics",
            description = "Total inbound, total outbound, net and movement count over the period, with a per-period "
                    + "breakdown (granularity DAY, WEEK or MONTH; default MONTH).")
    @GetMapping("/inbound-outbound")
    public InOutStatistics inboundOutbound(@RequestParam(required = false) Long productId,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "MONTH") Granularity granularity,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.inOutStatistics(user.warehouseScope(), productId, from, to, granularity);
    }

    @Operation(summary = "Inventory valuation",
            description = "Current inventory value grouped by PRODUCT, WAREHOUSE, CATEGORY or TOTAL. Cost is each "
                    + "batch's unit cost where known, otherwise the product's purchase price (never the selling price). "
                    + "Sortable by totalValue (default), totalQuantity, label.")
    @GetMapping("/inventory-valuation")
    public PageResponse<ValuationRow> inventoryValuation(@RequestParam ValuationGrouping groupBy,
                                                         @RequestParam(required = false) Long warehouseId,
                                                         @ParameterObject Pageable pageable,
                                                         @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.inventoryValuation(user.warehouseScope(), groupBy, warehouseId, pageable);
    }

    @Operation(summary = "Warehouse performance",
            description = "Per warehouse: product count, available stock, inventory value and its share, plus inbound "
                    + "and outbound activity over the period.")
    @GetMapping("/warehouse-performance")
    public PageResponse<WarehousePerformanceRow> warehousePerformance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                      @ParameterObject Pageable pageable,
                                                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.warehousePerformance(user.warehouseScope(), from, to, pageable);
    }

    @Operation(summary = "Supplier performance",
            description = "Per supplier over the order-date period: order count, purchased quantity and value, and "
                    + "completed, partially received and outstanding order counts (cancelled orders excluded).")
    @GetMapping("/supplier-performance")
    public PageResponse<SupplierPerformanceRow> supplierPerformance(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                                    @ParameterObject Pageable pageable,
                                                                    @AuthenticationPrincipal AuthenticatedUser user) {
        return reports.supplierPerformance(user.warehouseScope(), from, to, pageable);
    }
}
