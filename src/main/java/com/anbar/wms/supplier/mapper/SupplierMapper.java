package com.anbar.wms.supplier.mapper;

import com.anbar.wms.supplier.dto.SupplierResponse;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.common.web.AddressMapper;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = AddressMapper.class)
public interface SupplierMapper {

    @Mapping(target = "companyName", source = "name")
    @Mapping(target = "taxIdentificationNumber", source = "taxId")
    SupplierResponse toResponse(Supplier supplier);
}
