package com.anbar.wms.common.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

public record StatusRequest(@Schema(example = "false") @NotNull Boolean active) {
}
