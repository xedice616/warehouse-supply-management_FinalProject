package com.anbar.wms.warehouse.mapper;

import com.anbar.wms.common.web.AddressMapper;
import com.anbar.wms.warehouse.dto.WarehouseResponse;
import com.anbar.wms.warehouse.entity.Warehouse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(uses = AddressMapper.class)
public interface WarehouseMapper {

    @Mapping(target = "active", expression = "java(warehouse.isActive())")
    WarehouseResponse toResponse(Warehouse warehouse);
}  
