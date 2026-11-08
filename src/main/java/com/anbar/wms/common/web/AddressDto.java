package com.anbar.wms.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AddressDto(
        @Schema(example = "12 Heydar Aliyev Ave") @Size(max = 255) String addressLine,
        @Schema(example = "Baku") @Size(max = 100) String city,
        @Schema(example = "AZ1000") @Size(max = 20) String postalCode,
        @Schema(description = "ISO 3166-1 alpha-2", example = "AZ") @Pattern(regexp = "^[A-Za-z]{2}$") String countryCode) {
}
