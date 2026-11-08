package com.anbar.wms.supplier.service;

import com.anbar.wms.common.entity.Address;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceInUseException;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.supplier.dto.SupplierRequests;
import com.anbar.wms.supplier.dto.SupplierResponse;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.supplier.entity.SupplierDetails;
import com.anbar.wms.supplier.mapper.SupplierMapper;
import com.anbar.wms.supplier.repository.SupplierRepository;
import com.anbar.wms.common.web.AddressDto;
import com.anbar.wms.common.web.AddressMapper;
import java.util.Locale;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupplierService {

    private static final String RESOURCE = "Supplier";
    private static final String AZERBAIJAN = "AZ";
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("companyName", "name", "code", "code", "createdAt", "createdAt"), Sort.by("name").and(Sort.by("id")));

    private final SupplierRepository suppliers;
    private final SupplierMapper mapper;
    private final AddressMapper addressMapper;

    public SupplierService(SupplierRepository suppliers, SupplierMapper mapper, AddressMapper addressMapper) {
        this.suppliers = suppliers;
        this.mapper = mapper;
        this.addressMapper = addressMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<SupplierResponse> list(String search, Boolean active, Pageable pageable) {
        return PageResponse.from(suppliers.findAll(SupplierRepository.search(search, active), SORT.apply(pageable)),
                mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public SupplierResponse get(long id) {
        return mapper.toResponse(require(id));
    }

    @Transactional
    public SupplierResponse create(SupplierRequests.Create request) {
        SupplierDetails details = details(request.companyName(), request.taxIdentificationNumber(),
                request.contactPerson(), request.email(), request.phone(), request.address(), request.description(),
                request.paymentTermsDays());
        if (details.taxId() != null && suppliers.existsByTaxId(details.taxId())) {
            throw new DuplicateResourceException("taxIdentificationNumber");
        }
        String code = "SUP-%06d".formatted(suppliers.nextCodeNumber());
        return save(new Supplier(code, details));
    }

    @Transactional
    public SupplierResponse update(long id, SupplierRequests.Update request) {
        Supplier supplier = require(id);
        supplier.verifyVersion(request.version());
        SupplierDetails details = details(request.companyName(), request.taxIdentificationNumber(),
                request.contactPerson(), request.email(), request.phone(), request.address(), request.description(),
                request.paymentTermsDays());
        if (details.taxId() != null && suppliers.existsByTaxIdAndIdNot(details.taxId(), id)) {
            throw new DuplicateResourceException("taxIdentificationNumber");
        }
        supplier.update(details);
        return save(supplier);
    }

    @Transactional
    public SupplierResponse setActive(long id, boolean active) {
        Supplier supplier = require(id);
        if (active) {
            supplier.activate();
        } else {
            supplier.deactivate();
        }
        return save(supplier);
    }

    @Transactional
    public void delete(long id) {
        suppliers.delete(require(id));
        try {
            suppliers.flush();
        } catch (DataIntegrityViolationException ex) {
            throw new ResourceInUseException(RESOURCE);
        }
    }

    @Transactional(readOnly = true)
    public Supplier requireActive(long id) {
        Supplier supplier = require(id);
        if (!supplier.isActive()) {
            throw new InactiveResourceException(RESOURCE, id, supplier.getCode());
        }
        return supplier;
    }

    @Transactional(readOnly = true)
    public Supplier require(long id) {
        return suppliers.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    private SupplierDetails details(String companyName, String taxId, String contactPerson, String email,
                                    String phone, AddressDto addressDto, String description, Integer paymentTerms) {
        Address address = addressMapper.toAddress(addressDto);
        String normalizedTaxId = blankToNull(taxId) == null ? null : taxId.trim().toUpperCase(Locale.ROOT);
        if (normalizedTaxId != null && address != null && AZERBAIJAN.equals(address.countryCode())
                && !normalizedTaxId.matches("\\d{10}")) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "An Azerbaijani taxpayer identification number (VÖEN) has exactly 10 digits",
                    Map.of("field", "taxIdentificationNumber"));
        }
        return new SupplierDetails(companyName, normalizedTaxId, blankToNull(contactPerson), blankToNull(email),
                blankToNull(phone), address, blankToNull(description), paymentTerms == null ? 0 : paymentTerms);
    }

    private SupplierResponse save(Supplier supplier) {
        try {
            return mapper.toResponse(suppliers.saveAndFlush(supplier));
        } catch (DataIntegrityViolationException ex) {
            throw ConstraintViolations.duplicate(ex, Map.of("uq_supplier_tax_id", "taxIdentificationNumber"))
                    .orElseThrow(() -> ex);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
