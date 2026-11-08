package com.anbar.wms.supplier.dto;

import com.anbar.wms.common.web.AddressDto;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SupplierRequests {

    /** International-style phone: optional leading +, 7 to 15 digits, spaces, dashes and parentheses allowed. */
    public static final String PHONE_PATTERN = "^\\+?(?:[ ()-]*\\d){7,15}[ ()-]*$";

    /** 5–20 letters or digits; Azerbaijani VÖEN (10 digits) is checked additionally for AZ addresses. */
    public static final String TAX_ID_PATTERN = "^[A-Za-z0-9]{5,20}$";

    private SupplierRequests() {
    }

    public record Create(
            @Schema(example = "Caspian Fasteners LLC") @NotBlank @Size(max = 200) String companyName,
            @Schema(example = "1234567890") @Pattern(regexp = TAX_ID_PATTERN) String taxIdentificationNumber,
            @Schema(example = "Rauf Aliyev") @Size(max = 150) String contactPerson,
            @Schema(example = "sales@caspian-fasteners.az") @Email @Size(max = 254) String email,
            @Schema(example = "+994 12 555 01 23") @Pattern(regexp = PHONE_PATTERN) @Size(max = 30) String phone,
            @Valid AddressDto address,
            @Size(max = 1000) String description,
            @Schema(example = "30") @Min(0) @Max(365) Integer paymentTermsDays) {
    }

    public record Update(
            @NotBlank @Size(max = 200) String companyName,
            @Pattern(regexp = TAX_ID_PATTERN) String taxIdentificationNumber,
            @Size(max = 150) String contactPerson,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = PHONE_PATTERN) @Size(max = 30) String phone,
            @Valid AddressDto address,
            @Size(max = 1000) String description,
            @Min(0) @Max(365) Integer paymentTermsDays,
            @NotNull Long version) {
    }
}
