package com.anbar.wms.identity.dto;

import com.anbar.wms.common.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** A user changing their own password. The current password is re-checked before the change is applied. */
public record ChangePasswordRequest(
        @Schema(example = "Old-passw0rd") @NotBlank String currentPassword,
        @Schema(example = "New-passw0rd") @StrongPassword String newPassword) {

    /** Never let passwords reach logs. */
    @Override
    public String toString() {
        return "ChangePasswordRequest[currentPassword=****, newPassword=****]";
    }
}
