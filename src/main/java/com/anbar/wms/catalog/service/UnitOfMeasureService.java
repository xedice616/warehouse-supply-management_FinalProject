package com.anbar.wms.catalog.service;

import com.anbar.wms.catalog.dto.CatalogResponses.UnitResponse;
import com.anbar.wms.catalog.dto.UnitRequests;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.catalog.mapper.CatalogMapper;
import com.anbar.wms.catalog.repository.CatalogSpecifications;
import com.anbar.wms.catalog.repository.ProductRepository;
import com.anbar.wms.catalog.repository.UnitOfMeasureRepository;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceInUseException;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UnitOfMeasureService {

    private static final String RESOURCE = "Unit of measure";
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("code", "code", "name", "name", "createdAt", "createdAt"), Sort.by("code"));

    private final UnitOfMeasureRepository units;
    private final ProductRepository products;
    private final CatalogMapper mapper;

    public UnitOfMeasureService(UnitOfMeasureRepository units, ProductRepository products, CatalogMapper mapper) {
        this.units = units;
        this.products = products;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<UnitResponse> list(Boolean active, Pageable pageable) {
        return PageResponse.from(units.findAll(CatalogSpecifications.units(active), SORT.apply(pageable)),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public UnitResponse get(long id) {
        return mapper.toResponse(require(id));
    }

    @Transactional
    public UnitResponse create(UnitRequests.Create request) {
        String code = UnitOfMeasure.normalizeCode(request.code());
        if (units.existsByCode(code)) {
            throw new DuplicateResourceException("code");
        }
        UnitOfMeasure unit = new UnitOfMeasure(code, request.name(), request.decimalScale());
        try {
            return mapper.toResponse(units.saveAndFlush(unit));
        } catch (DataIntegrityViolationException ex) {
            throw ConstraintViolations.duplicate(ex, Map.of("uq_unit_of_measure_code", "code")).orElseThrow(() -> ex);
        }
    }

    @Transactional
    public UnitResponse update(long id, UnitRequests.Update request) {
        UnitOfMeasure unit = require(id);
        unit.verifyVersion(request.version());
        if (unit.getDecimalScale() != request.decimalScale() && products.existsByUnitOfMeasureId(id)) {
            throw new ResourceInUseException(RESOURCE, "The decimal scale of a unit used by products cannot change");
        }
        unit.update(request.name(), request.decimalScale());
        return mapper.toResponse(units.saveAndFlush(unit));
    }

    @Transactional
    public UnitResponse setActive(long id, boolean active) {
        UnitOfMeasure unit = require(id);
        if (active) {
            unit.activate();
        } else {
            unit.deactivate();
        }
        return mapper.toResponse(units.saveAndFlush(unit));
    }

    @Transactional
    public void delete(long id) {
        units.delete(require(id));
        try {
            units.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceInUseException(RESOURCE);
        }
    }

    UnitOfMeasure requireActive(long id) {
        UnitOfMeasure unit = require(id);
        if (!unit.isActive()) {
            throw new InactiveResourceException(RESOURCE, id, unit.getCode());
        }
        return unit;
    }

    UnitOfMeasure require(long id) {
        return units.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }
}
