package com.anbar.wms.inventory.service;

import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.inventory.dto.MovementSearch;
import com.anbar.wms.inventory.dto.StockMovementResponse;
import com.anbar.wms.inventory.entity.StockMovement;
import com.anbar.wms.inventory.mapper.StockMovementMapper;
import com.anbar.wms.inventory.repository.StockMovementRepository;
import com.anbar.wms.inventory.repository.StockMovementSpecifications;
import com.anbar.wms.security.WarehouseScope;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only movement history, limited to the warehouses the caller may see. */
@Service
@Transactional(readOnly = true)
public class StockMovementQueryService {

    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("movementDate", "performedAt", "quantity", "quantity", "id", "id"),
            Sort.by(Sort.Order.desc("performedAt"), Sort.Order.desc("id")));

    private final StockMovementRepository movements;
    private final StockMovementMapper mapper;
    private final WmsProperties properties;

    public StockMovementQueryService(StockMovementRepository movements, StockMovementMapper mapper,
                                     WmsProperties properties) {
        this.movements = movements;
        this.mapper = mapper;
        this.properties = properties;
    }

    public PageResponse<StockMovementResponse> search(MovementSearch search, WarehouseScope scope, Pageable pageable) {
        if (search.from() != null && search.to() != null && search.from().isAfter(search.to())) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "'from' must not be after 'to'",
                    Map.of("field", "from"));
        }
        return PageResponse.from(movements.findAll(StockMovementSpecifications.matching(scope,
                        search.productId(), search.batchId(), search.warehouseId(), search.type(), search.supplierId(),
                        search.referenceNumber(), startOf(search.from()), startOfDayAfter(search.to())),
                SORT.apply(pageable)), mapper::toResponse);
    }

    public StockMovementResponse get(long id, WarehouseScope scope) {
        StockMovement movement = movements.findById(id)
                .filter(m -> visible(m, scope))
                .orElseThrow(() -> new ResourceNotFoundException("Stock movement", id));
        return mapper.toResponse(movement);
    }

    private static boolean visible(StockMovement movement, WarehouseScope scope) {
        return scope.unrestricted()
                || (movement.getSourceWarehouse() != null && scope.allows(movement.getSourceWarehouse().getId()))
                || (movement.getDestinationWarehouse() != null && scope.allows(movement.getDestinationWarehouse().getId()));
    }

    private Instant startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(properties.businessTimezone()).toInstant();
    }

    private Instant startOfDayAfter(LocalDate date) {
        return date == null ? null : startOf(date.plusDays(1));
    }
}
