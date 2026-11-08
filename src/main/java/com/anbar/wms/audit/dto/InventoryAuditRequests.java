package com.anbar.wms.audit.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

public final class InventoryAuditRequests {

    private InventoryAuditRequests() {
    }

    /**
     * @param fullAudit default false; true starts with every stocked item of the warehouse (one open full audit per warehouse)
     * @param blind     default false; hides system quantities from the response until counting is over
     */
    public record Create(
            @NotNull @Positive Long warehouseId,
            Boolean fullAudit,
            Boolean blind,
            @Size(max = 1000) String notes) {
    }

    /** @param batchNumber optional for batch-tracked products: without it every stocked batch is added */
    public record Item(
            @NotNull @Positive Long productId,
            @Size(max = 64) String batchNumber) {
    }

    public record AddItems(@NotEmpty @Size(max = 500) List<@Valid @NotNull Item> items) {
    }

    public record Count(
            @NotNull @DecimalMin("0") @Digits(integer = 15, fraction = 4) BigDecimal countedQuantity,
            @Size(max = 500) String notes) {
    }

    public record Reason(@NotBlank @Size(max = 500) String reason) {
    }
}
