package com.anbar.wms.audit.service;

import com.anbar.wms.audit.dto.InventoryAuditResponses.DiscrepancyRow;
import com.anbar.wms.audit.dto.InventoryAuditResponses.Statistics;
import com.anbar.wms.audit.dto.InventoryAuditResponses.WarehouseStatistics;
import com.anbar.wms.audit.mapper.InventoryAuditMapper;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.audit.repository.InventoryAuditQuery;
import com.anbar.wms.audit.repository.InventoryAuditQuery.Filter;
import com.anbar.wms.audit.repository.InventoryAuditQuery.Totals;
import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.security.WarehouseScope;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Discrepancy and accuracy reports over audits the caller may see; dates filter the audit's creation day. */
@Service
@Transactional(readOnly = true)
public class InventoryAuditReportService {

    private final InventoryAuditQuery query;
    private final InventoryAuditMapper mapper;
    private final WmsProperties properties;

    public InventoryAuditReportService(InventoryAuditQuery query, InventoryAuditMapper mapper, WmsProperties properties) {
        this.query = query;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** Counted items whose count differed from the system. Sortable by discrepancy (absolute, default), countedAt, auditNumber, sku. */
    public PageResponse<DiscrepancyRow> discrepancies(WarehouseScope scope, Long warehouseId, AuditStatus status,
                                                      LocalDate from, LocalDate to, Pageable pageable) {
        return PageResponse.from(query.discrepancies(filter(scope, warehouseId, status, from, to), pageable),
                mapper::toDiscrepancy);
    }

    public List<WarehouseStatistics> byWarehouse(WarehouseScope scope, LocalDate from, LocalDate to) {
        return query.byWarehouse(filter(scope, null, null, from, to)).stream().map(mapper::toStatistics).toList();
    }

    public Statistics statistics(WarehouseScope scope, Long warehouseId, LocalDate from, LocalDate to) {
        Totals totals = query.totals(filter(scope, warehouseId, null, from, to));
        Map<AuditStatus, Long> byStatus = new EnumMap<>(AuditStatus.class);
        for (AuditStatus status : AuditStatus.values()) {
            byStatus.put(status, totals.auditsByStatus().getOrDefault(status.name(), 0L));
        }
        long audits = byStatus.values().stream().mapToLong(Long::longValue).sum();
        BigDecimal accuracy = totals.countedItems() == 0 ? null
                : BigDecimal.valueOf(totals.countedItems() - totals.discrepancyItems())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totals.countedItems()), 2, RoundingMode.HALF_UP);
        return new Statistics(audits, byStatus, totals.countedItems(), totals.discrepancyItems(), accuracy,
                totals.surplus(), totals.shortage());
    }

    private Filter filter(WarehouseScope scope, Long warehouseId, AuditStatus status, LocalDate from, LocalDate to) {
        return new Filter(scope.unrestricted() ? null : scope.warehouseIds(), warehouseId,
                status == null ? null : status.name(), startOf(from), startOf(to == null ? null : to.plusDays(1)));
    }

    private Instant startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(properties.businessTimezone()).toInstant();
    }
}
