package com.anbar.wms.warehouse.dto;

import com.anbar.wms.common.web.AddressDto;

import java.time.Instant;

public record WarehouseResponse(
        long id,
        String code,
        String name,
        String description,
        AddressDto address,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
