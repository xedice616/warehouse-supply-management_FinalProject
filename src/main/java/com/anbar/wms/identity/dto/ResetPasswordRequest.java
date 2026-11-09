package com.anbar.wms.identity.dto;

import com.anbar.wms.common.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;

/** An administrator setting a new password for a user (e.g. after a lockout). The user is also unlocked. */
public record ResetPasswordRequest(
        @Schema(example = "Temp-passw0rd") @StrongPassword String newPassword) {

    /** Never let the password reach logs. */
    @Override
    public String toString() {
        return "ResetPasswordRequest[newPassword=****]";
    }
}
