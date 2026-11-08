package com.anbar.wms.warehouse.dto;

import com.anbar.wms.common.web.AddressDto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class WarehouseRequests {

    private WarehouseRequests() {
    }

    public record Create(
            @Schema(description = "Unique, stored upper-case, cannot be changed later", example = "BAK-01")
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,19}$") String code,
            @Schema(example = "Baku central warehouse") @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @Valid AddressDto address) {
    }

    public record Update(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 1000) String description,
            @Valid AddressDto address,
            @NotNull Long version) {
    }
}
