package com.anbar.wms.common.web;

import com.anbar.wms.common.entity.Address;
import java.util.Locale;
import org.mapstruct.Mapper;

@Mapper
public interface AddressMapper {

    AddressDto toDto(Address address);

    /** Blank parts become null; an address with no parts at all becomes null. Country codes are upper-cased. */
    default Address toAddress(AddressDto dto) {
        if (dto == null) {
            return null;
        }
        String line = blankToNull(dto.addressLine());
        String city = blankToNull(dto.city());
        String postalCode = blankToNull(dto.postalCode());
        String country = blankToNull(dto.countryCode());
        if (line == null && city == null && postalCode == null && country == null) {
            return null;
        }
        return new Address(line, city, postalCode, country == null ? null : country.toUpperCase(Locale.ROOT));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
