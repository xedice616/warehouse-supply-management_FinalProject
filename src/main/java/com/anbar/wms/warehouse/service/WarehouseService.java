package com.anbar.wms.warehouse.service;

import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.exception.StockOnHandException;
import com.anbar.wms.common.web.AddressMapper;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.warehouse.dto.WarehouseRequests;
import com.anbar.wms.warehouse.dto.WarehouseResponse;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.mapper.WarehouseMapper;
import com.anbar.wms.warehouse.repository.WarehouseRepository;
import com.anbar.wms.warehouse.repository.WarehouseSpecifications;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {

    private static final String RESOURCE = "Warehouse";
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("code", "code", "name", "name", "createdAt", "createdAt"), Sort.by("code"));

    private final WarehouseRepository warehouses;
    private final WarehouseStockInfo stockInfo;
    private final WarehouseMapper mapper;
    private final AddressMapper addressMapper;

    public WarehouseService(WarehouseRepository warehouses, WarehouseStockInfo stockInfo, WarehouseMapper mapper,
                            AddressMapper addressMapper) {
        this.addressMapper = addressMapper;
        this.warehouses = warehouses;
        this.stockInfo = stockInfo;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<WarehouseResponse> list(WarehouseScope scope, String search, Boolean active, Pageable pageable) {
        return PageResponse.from(
                warehouses.findAll(WarehouseSpecifications.warehouses(scope, search, active), SORT.apply(pageable)),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public WarehouseResponse get(long id, WarehouseScope scope) {
        return mapper.toResponse(requireVisible(id, scope));
    }

    /** Non-throwing lookup by code for callers (e.g. CSV import) that report a missing warehouse as data. */
    @Transactional(readOnly = true)
    public java.util.Optional<WarehouseResponse> lookupByCode(String code) {
        return warehouses.findByCode(Warehouse.normalizeCode(code)).map(mapper::toResponse);
    }

    @Transactional
    public WarehouseResponse create(WarehouseRequests.Create request) {
        String code = Warehouse.normalizeCode(request.code());
        if (warehouses.existsByCode(code)) {
            throw new DuplicateResourceException("code");
        }
        Warehouse warehouse = new Warehouse(code, request.name(), blankToNull(request.description()),
                addressMapper.toAddress(request.address()));
        try {
            return mapper.toResponse(warehouses.saveAndFlush(warehouse));
        } catch (DataIntegrityViolationException ex) {
            throw ConstraintViolations.duplicate(ex, Map.of("uq_warehouse_code", "code")).orElseThrow(() -> ex);
        }
    }

    @Transactional
    public WarehouseResponse update(long id, WarehouseRequests.Update request) {
        Warehouse warehouse = require(id);
        warehouse.verifyVersion(request.version());
        warehouse.update(request.name(), blankToNull(request.description()), addressMapper.toAddress(request.address()));
        return mapper.toResponse(warehouses.saveAndFlush(warehouse));
    }

    /**
     * Deactivation locks the warehouse row first; stock postings lock it too, so no stock can arrive between
     * the "is it empty" check and the commit.
     */
    @Transactional
    public WarehouseResponse setActive(long id, boolean active) {
        Warehouse warehouse = warehouses.lockById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        if (active) {
            warehouse.activate();
        } else {
            if (stockInfo.warehouseHasStockOnHand(id)) {
                throw new StockOnHandException(RESOURCE,
                        "A warehouse with stock on hand cannot be deactivated; transfer or write off the stock first");
            }
            warehouse.deactivate();
        }
        return mapper.toResponse(warehouses.saveAndFlush(warehouse));
    }

    /** Out-of-scope warehouses are reported as missing, so their existence isn't revealed. */
    @Transactional(readOnly = true)
    public Warehouse requireVisible(long id, WarehouseScope scope) {
        if (!scope.allows(id)) {
            throw new ResourceNotFoundException(RESOURCE, id);
        }
        return require(id);
    }

    /**
     * For a stock posting: the warehouse must be visible to the caller and active, and stays so until the posting
     * commits (a shared row lock that deactivation has to wait for). Callers lock in ascending id order.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Warehouse lockForStockOperation(long id, WarehouseScope scope) {
        if (!scope.allows(id)) {
            throw new ResourceNotFoundException(RESOURCE, id);
        }
        Warehouse warehouse = warehouses.lockSharedById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        if (!warehouse.isActive()) {
            throw new InactiveResourceException(RESOURCE, id, warehouse.getCode());
        }
        return warehouse;
    }

    /** For new stock operations and manager assignments: the warehouse must exist and be active. */
    @Transactional(readOnly = true)
    public Warehouse requireActive(long id) {
        Warehouse warehouse = require(id);
        if (!warehouse.isActive()) {
            throw new InactiveResourceException(RESOURCE, id, warehouse.getCode());
        }
        return warehouse;
    }

    @Transactional(readOnly = true)
    public Warehouse require(long id) {
        return warehouses.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
