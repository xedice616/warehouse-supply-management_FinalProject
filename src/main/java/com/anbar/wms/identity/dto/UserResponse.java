package com.anbar.wms.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

/**
 * A user as the API shows it. Deliberately has no password hash, lockout counters or other security internals.
 *
 * @param status     {@code ACTIVE} or {@code DISABLED}
 * @param roles      role codes, sorted
 * @param warehouses warehouses the user may operate on (administrators may operate on all)
 */
public record UserResponse(
        long id,
        @Schema(example = "aysel.m") String username,
        @Schema(example = "aysel@example.com") String email,
        @Schema(example = "Aysel Mammadova") String fullName,
        @Schema(example = "ACTIVE") String status,
        @Schema(example = "[\"USER\"]") List<String> roles,
        List<WarehouseSummary> warehouses,
        Instant createdAt,
        Instant lastLoginAt) {

    public UserResponse {
        roles = List.copyOf(roles);
        warehouses = List.copyOf(warehouses);
    }
}
