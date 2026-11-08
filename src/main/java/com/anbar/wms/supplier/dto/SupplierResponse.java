package com.anbar.wms.supplier.dto;

import com.anbar.wms.common.web.AddressDto;
import java.time.Instant;

public record SupplierResponse(
        long id,
        String code,
        String companyName,
        String taxIdentificationNumber,
        String contactPerson,
        String email,
        String phone,
        AddressDto address,
        String description,
        int paymentTermsDays,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
