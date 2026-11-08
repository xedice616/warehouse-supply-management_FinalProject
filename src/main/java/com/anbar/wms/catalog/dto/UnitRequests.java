package com.anbar.wms.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UnitRequests {

    private UnitRequests() {
    }

    public record Create(
            @Schema(example = "BOTTLE") @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]{1,16}$") String code,
            @Schema(example = "Bottle") @NotBlank @Size(max = 64) String name,
            @Schema(description = "Decimal places allowed in quantities (0 for countable items)", example = "0")
            @NotNull @Min(0) @Max(4) Integer decimalScale) {
    }

    public record Update(
            @NotBlank @Size(max = 64) String name,
            @NotNull @Min(0) @Max(4) Integer decimalScale,
            @Schema(description = "Version from the last read; a mismatch means someone else changed it")
            @NotNull Long version) {
    }
}
