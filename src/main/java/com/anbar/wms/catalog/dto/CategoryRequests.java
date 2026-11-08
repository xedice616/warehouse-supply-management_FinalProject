package com.anbar.wms.catalog.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class CategoryRequests {

    private CategoryRequests() {
    }

    public record Create(
            @Schema(example = "Fasteners") @NotBlank @Size(max = 100) String name,
            @Schema(description = "Omit for a root category") @Positive Long parentId) {
    }

    public record Update(
            @NotBlank @Size(max = 100) String name,
            @Positive Long parentId,
            @NotNull Long version) {
    }
}
