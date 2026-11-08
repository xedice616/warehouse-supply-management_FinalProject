package com.anbar.wms.identity.dto;

import com.anbar.wms.security.RoleCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.Set;

/** Replaces all roles of a user. A user always keeps at least one role. */
public record UpdateRolesRequest(
        @Schema(example = "[\"WAREHOUSE_MANAGER\"]")
        @NotEmpty Set<@NotNull RoleCode> roles) {
}
