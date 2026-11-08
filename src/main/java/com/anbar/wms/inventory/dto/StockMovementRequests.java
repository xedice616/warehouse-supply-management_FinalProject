package com.anbar.wms.inventory.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** Requests for stock movements. Quantities are always positive; the movement type gives the direction. */
public final class StockMovementRequests {

    private StockMovementRequests() {
    }

    public enum AdjustmentDirection { INCREASE, DECREASE }

    public record Inbound(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long warehouseId,
            @Schema(example = "100") @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4)
            BigDecimal quantity,
            @Schema(description = "Required for batch-tracked products", example = "LOT-2026-11")
            @Size(max = 64) String batchNumber,
            @Schema(description = "Required for a new batch of an expiry-tracked product") LocalDate expiresOn,
            LocalDate manufacturedOn,
            @Schema(description = "Defaults to the product's purchase price")
            @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
            @Positive Long supplierId,
            @Schema(example = "INV-55120") @Size(max = 64) String referenceNumber,
            @Size(max = 500) String reason) {
    }

    public record Outbound(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long warehouseId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 64) String batchNumber,
            @Schema(example = "SO-1042") @Size(max = 64) String referenceNumber,
            @Size(max = 500) String reason) {
    }

    public record Transfer(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long sourceWarehouseId,
            @NotNull @Positive Long destinationWarehouseId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 64) String batchNumber,
            @Size(max = 64) String referenceNumber,
            @Size(max = 500) String reason) {

        @AssertTrue(message = "source and destination warehouses must differ")
        @Schema(hidden = true)
        public boolean isDifferentWarehouses() {
            return sourceWarehouseId == null || !Objects.equals(sourceWarehouseId, destinationWarehouseId);
        }
    }

    public record Adjustment(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long warehouseId,
            @Schema(description = "INCREASE adds stock, DECREASE removes it") @NotNull AdjustmentDirection direction,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 64) String batchNumber,
            @Schema(description = "Required for a new batch of an expiry-tracked product") LocalDate expiresOn,
            @Size(max = 64) String referenceNumber,
            @Schema(example = "Recount after shelf reorganisation") @NotBlank @Size(max = 500) String reason) {
    }

    public record WriteOff(
            @NotNull @Positive Long productId,
            @NotNull @Positive Long warehouseId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 15, fraction = 4) BigDecimal quantity,
            @Size(max = 64) String batchNumber,
            @Size(max = 64) String referenceNumber,
            @Schema(example = "Water damage") @NotBlank @Size(max = 500) String reason) {
    }
}
