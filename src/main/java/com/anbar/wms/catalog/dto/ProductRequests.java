package com.anbar.wms.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

public final class ProductRequests {

    private ProductRequests() {
    }

    public record Create(
            @Schema(description = "Unique, stored upper-case, cannot be changed later", example = "BOLT-M8-40")
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$") String sku,
            @Schema(example = "Hex bolt M8x40") @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Schema(example = "4006381333931") @Pattern(regexp = "^[0-9A-Za-z-]{1,64}$") String barcode,
            @NotNull @Positive Long categoryId,
            @NotNull @Positive Long unitId,
            Boolean batchTracked,
            Boolean expiryTracked,
            @Schema(example = "100") @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal minStockQuantity,
            @Schema(example = "0.18") @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal purchasePrice,
            @Schema(example = "0.35") @DecimalMin("0") @Digits(integer = 15, fraction = 4)
            BigDecimal sellingPrice) {

        @AssertTrue(message = "an expiry-tracked product must also be batch-tracked")
        @Schema(hidden = true)
        public boolean isTrackingConsistent() {
            return !Boolean.TRUE.equals(expiryTracked) || Boolean.TRUE.equals(batchTracked);
        }
    }

    public record Update(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Pattern(regexp = "^[0-9A-Za-z-]{1,64}$") String barcode,
            @NotNull @Positive Long categoryId,
            @NotNull @Positive Long unitId,
            Boolean batchTracked,
            Boolean expiryTracked,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal minStockQuantity,
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal purchasePrice,
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal sellingPrice,
            @NotNull Long version) {

        @AssertTrue(message = "an expiry-tracked product must also be batch-tracked")
        @Schema(hidden = true)
        public boolean isTrackingConsistent() {
            return !Boolean.TRUE.equals(expiryTracked) || Boolean.TRUE.equals(batchTracked);
        }
    }
}
