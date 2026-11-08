package com.anbar.wms.supplier.entity;

import com.anbar.wms.common.entity.Address;

public record SupplierDetails(
        String name,
        String taxId,
        String contactPerson,
        String email,
        String phone,
        Address address,
        String description,
        int paymentTermsDays) {
}
