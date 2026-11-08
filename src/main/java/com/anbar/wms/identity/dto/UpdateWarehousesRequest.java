package com.anbar.wms.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.Set;

/** Replaces the set of warehouses a user may see and operate on. An empty set removes all access. */
public record UpdateWarehousesRequest(
        @Schema(example = "[1, 2]") @NotNull Set<@NotNull @Positive Long> warehouseIds) {
}
