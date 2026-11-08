package com.anbar.wms.reporting.service;

import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.reporting.model.ReportResponses.InOutStatistics;
import com.anbar.wms.reporting.model.ReportResponses.MovementReportRow;
import com.anbar.wms.reporting.model.ReportResponses.SupplierPerformanceRow;
import com.anbar.wms.reporting.model.ReportResponses.TopProductRow;
import com.anbar.wms.reporting.model.ReportResponses.ValuationRow;
import com.anbar.wms.reporting.model.ReportResponses.WarehousePerformanceRow;
import com.anbar.wms.reporting.repository.ReportQueries;
import com.anbar.wms.reporting.model.ReportParams.Granularity;
import com.anbar.wms.reporting.repository.ReportQueries.MovementFilter;
import com.anbar.wms.reporting.repository.ReportQueries.Scope;
import com.anbar.wms.reporting.model.ReportParams.ValuationGrouping;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.security.WarehouseScope;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Analytical reports. Each report is limited to the warehouses the caller may see; dates are inclusive calendar days. */
@Service
@Transactional(readOnly = true)
public class ReportService {

    private final ReportQueries queries;
    private final WmsProperties properties;

    public ReportService(ReportQueries queries, WmsProperties properties) {
        this.queries = queries;
        this.properties = properties;
    }

    public PageResponse<MovementReportRow> stockMovements(WarehouseScope scope, Long productId, Long warehouseId,
            MovementType type, Long performedByUserId, LocalDate from, LocalDate to, Pageable pageable) {
        requireOrder(from, to);
        return scoped(scope).map(s -> PageResponse.from(queries.movements(
                new MovementFilter(s, productId, warehouseId, type == null ? null : type.name(), performedByUserId,
                        startOf(from), startOfDayAfter(to)), pageable)))
                .orElseGet(() -> PageResponse.from(Page.<MovementReportRow>empty(pageable)));
    }

    public PageResponse<TopProductRow> topProducts(WarehouseScope scope, LocalDate from, LocalDate to, Pageable pageable) {
        requireOrder(from, to);
        return scoped(scope).map(s -> PageResponse.from(queries.topProducts(s, startOf(from), startOfDayAfter(to), pageable)))
                .orElseGet(() -> PageResponse.from(Page.<TopProductRow>empty(pageable)));
    }

    public InOutStatistics inOutStatistics(WarehouseScope scope, Long productId, LocalDate from, LocalDate to,
                                           Granularity granularity) {
        requireOrder(from, to);
        return scoped(scope)
                .map(s -> queries.inOutStatistics(s, productId, startOf(from), startOfDayAfter(to), granularity,
                        properties.businessTimezone().getId()))
                .orElseGet(() -> new InOutStatistics(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, List.of()));
    }

    public PageResponse<ValuationRow> inventoryValuation(WarehouseScope scope, ValuationGrouping grouping,
                                                         Long warehouseId, Pageable pageable) {
        return scoped(scope).map(s -> PageResponse.from(queries.valuation(s, grouping, warehouseId, pageable)))
                .orElseGet(() -> PageResponse.from(Page.<ValuationRow>empty(pageable)));
    }

    public PageResponse<WarehousePerformanceRow> warehousePerformance(WarehouseScope scope, LocalDate from, LocalDate to,
                                                                      Pageable pageable) {
        requireOrder(from, to);
        return scoped(scope).map(s -> PageResponse.from(
                        queries.warehousePerformance(s, startOf(from), startOfDayAfter(to), pageable)))
                .orElseGet(() -> PageResponse.from(Page.<WarehousePerformanceRow>empty(pageable)));
    }

    public PageResponse<SupplierPerformanceRow> supplierPerformance(WarehouseScope scope, LocalDate from, LocalDate to,
                                                                    Pageable pageable) {
        requireOrder(from, to);
        return scoped(scope).map(s -> PageResponse.from(queries.supplierPerformance(s, from, to, pageable)))
                .orElseGet(() -> PageResponse.from(Page.<SupplierPerformanceRow>empty(pageable)));
    }

    /** Empty optional means a non-admin whose scope is empty: every report is empty for them. */
    private static Optional<Scope> scoped(WarehouseScope scope) {
        if (scope.unrestricted()) {
            return Optional.of(new Scope(true, List.of(-1L)));
        }
        return scope.warehouseIds().isEmpty() ? Optional.empty() : Optional.of(new Scope(false, scope.warehouseIds()));
    }

    private static void requireOrder(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "'from' must not be after 'to'",
                    Map.of("field", "from"));
        }
    }

    private Instant startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(properties.businessTimezone()).toInstant();
    }

    private Instant startOfDayAfter(LocalDate date) {
        return date == null ? null : startOf(date.plusDays(1));
    }
}
