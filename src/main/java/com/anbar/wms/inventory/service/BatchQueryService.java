package com.anbar.wms.inventory.service;

import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.inventory.dto.BatchResponses.BatchStockResponse;
import com.anbar.wms.inventory.dto.BatchResponses.ExpirationRiskResponse;
import com.anbar.wms.inventory.dto.BatchResponses.ProductRef;
import com.anbar.wms.inventory.dto.BatchResponses.WarehouseRef;
import com.anbar.wms.inventory.repository.BatchStockQuery;
import com.anbar.wms.inventory.repository.BatchStockQuery.Filter;
import com.anbar.wms.inventory.repository.BatchStockQuery.Row;
import com.anbar.wms.inventory.repository.StockBatchRepository;
import com.anbar.wms.security.WarehouseScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Batch stock and expiration reports, limited to the warehouses the caller may see. */
@Service
@Transactional(readOnly = true)
public class BatchQueryService {

    public static final int MAX_HORIZON_DAYS = 3650;
    private static final Sort BY_EXPIRY = Sort.by("expirationDate", "batchNumber");

    private final BatchStockQuery query;
    private final StockBatchRepository batches;
    private final WmsProperties properties;
    private final Clock clock;

    public BatchQueryService(BatchStockQuery query, StockBatchRepository batches, WmsProperties properties, Clock clock) {
        this.query = query;
        this.batches = batches;
        this.properties = properties;
        this.clock = clock;
    }

    public PageResponse<BatchStockResponse> search(Long productId, Long warehouseId, String batchNumber,
                                                   boolean includeEmpty, WarehouseScope scope, Pageable pageable) {
        return find(new Filter(ids(scope), null, productId, warehouseId, batchNumber, includeEmpty, null, null),
                pageable, BY_EXPIRY);
    }

    /** One batch with its stock in every warehouse the caller may see. */
    public PageResponse<BatchStockResponse> get(long batchId, WarehouseScope scope, Pageable pageable) {
        if (!batches.existsById(batchId)) {
            throw new ResourceNotFoundException("Batch", batchId);
        }
        return find(new Filter(ids(scope), batchId, null, null, null, true, null, null), pageable, Sort.by("batchNumber"));
    }

    /** Batches whose expiration date has passed and that still hold stock. */
    public PageResponse<BatchStockResponse> expired(Long warehouseId, WarehouseScope scope, Pageable pageable) {
        return find(new Filter(ids(scope), null, null, warehouseId, null, false, null, today()), pageable, BY_EXPIRY);
    }

    /** Batches with stock expiring from today to {@code days} days ahead (inclusive). */
    public PageResponse<BatchStockResponse> expiring(int days, Long warehouseId, WarehouseScope scope, Pageable pageable) {
        LocalDate today = today();
        return find(new Filter(ids(scope), null, null, warehouseId, null, false, today, today.plusDays(horizon(days) + 1L)),
                pageable, BY_EXPIRY);
    }

    public PageResponse<ExpirationRiskResponse> expirationRisk(int days, Long warehouseId, WarehouseScope scope,
                                                               Pageable pageable) {
        LocalDate today = today();
        return PageResponse.from(query.risk(ids(scope), warehouseId, today, today.plusDays(horizon(days)), unsorted(pageable)),
                r -> new ExpirationRiskResponse(new ProductRef(r.productId(), r.sku(), r.productName(), r.unitCode()),
                        r.batchCount(), r.expiredQuantity(), r.expiringQuantity(), r.earliestExpiration()));
    }

    private PageResponse<BatchStockResponse> find(Filter filter, Pageable pageable, Sort defaultSort) {
        LocalDate today = today();
        return PageResponse.from(query.find(filter, pageable, defaultSort), row -> toResponse(row, today));
    }

    private static BatchStockResponse toResponse(Row row, LocalDate today) {
        Long days = row.expirationDate() == null ? null : ChronoUnit.DAYS.between(today, row.expirationDate());
        return new BatchStockResponse(row.batchId(), row.batchNumber(),
                new ProductRef(row.productId(), row.sku(), row.productName(), row.unitCode()),
                new WarehouseRef(row.warehouseId(), row.warehouseCode(), row.warehouseName()),
                row.initialQuantity(), row.availableQuantity(), row.productionDate(), row.expirationDate(), days,
                days != null && days < 0, row.purchaseOrderItemId(), row.unitCost(), row.createdAt(), row.updatedAt());
    }

    private static Collection<Long> ids(WarehouseScope scope) {
        return scope.unrestricted() ? null : scope.warehouseIds();
    }

    private static int horizon(int days) {
        if (days < 0 || days > MAX_HORIZON_DAYS) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "days must be between 0 and " + MAX_HORIZON_DAYS, Map.of("field", "days"));
        }
        return days;
    }

    private static Pageable unsorted(Pageable pageable) {
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessTimezone()));
    }
}
