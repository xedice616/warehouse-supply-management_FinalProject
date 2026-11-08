package com.anbar.wms.purchasing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Purchase order requests. There is no total field: the total is always computed from the items. */
public final class PurchaseOrderRequests {

    private PurchaseOrderRequests() {
    }

    public record Item(
            @NotNull @Positive Long productId,
            @Schema(example = "500") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4)
            BigDecimal orderedQuantity,
            @Schema(example = "0.18") @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @Size(max = 500) String notes) {
    }

    public record Create(
            @NotNull @Positive Long supplierId,
            @NotNull @Positive Long warehouseId,
            @Schema(description = "ISO 4217, default AZN", example = "AZN") @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Schema(description = "Defaults to today") LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull Item> items) {
    }

    public record Update(
            @NotNull @Positive Long supplierId,
            @NotNull @Positive Long warehouseId,
            @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @NotNull LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            @Size(max = 1000) String notes,
            @NotEmpty @Size(max = 200) List<@Valid @NotNull Item> items,
            @NotNull Long version) {
    }

    public record Reason(@Schema(example = "Supplier price too high") @NotBlank @Size(max = 500) String reason) {
    }

    public record ReceiptItem(
            @Schema(description = "Id of the purchase order item") @NotNull @Positive Long itemId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Schema(description = "Required for batch-tracked products", example = "LOT-7731") @Size(max = 64) String batchNumber,
            @Schema(description = "Required for a new batch of an expiry-tracked product") LocalDate expirationDate,
            LocalDate productionDate) {
    }

    public record Receipt(@NotEmpty @Size(max = 200) List<@Valid @NotNull ReceiptItem> items) {
    }
}
