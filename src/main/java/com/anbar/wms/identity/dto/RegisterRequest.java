package com.anbar.wms.identity.dto;

import com.anbar.wms.common.validation.StrongPassword;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Self-registration. The new account always gets the USER role; only an administrator can grant more. */
public record RegisterRequest(

        @Schema(description = "3–50 characters: letters, digits, '.', '_' or '-'; stored lower-case",
                example = "aysel.m")
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._-]{2,49}$",
                message = "must be 3-50 characters: letters, digits, '.', '_' or '-', starting with a letter or digit")
        String username,

        @Schema(description = "Unique (case-insensitive); stored lower-case", example = "aysel@example.com")
        @NotBlank @Email @Size(max = 254)
        String email,

        @Schema(description = "10–64 characters with at least one letter and one digit", example = "Warehouse2026!",
                accessMode = Schema.AccessMode.WRITE_ONLY)
        @StrongPassword
        String password,

        @Schema(example = "Aysel Mammadova")
        @NotBlank @Size(max = 150)
        String fullName) {

    /** Never let the password reach logs. */
    @Override
    public String toString() {
        return "RegisterRequest[username=" + username + ", email=" + email + ", password=****, fullName=" + fullName + "]";
    }
}
