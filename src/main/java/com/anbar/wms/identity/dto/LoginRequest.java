package com.anbar.wms.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Login with either the username or the email address. */
public record LoginRequest(

        @Schema(description = "Username or email (case-insensitive)", example = "aysel.m")
        @NotBlank @Size(max = 254)
        String usernameOrEmail,

        @Schema(example = "Warehouse2026!", accessMode = Schema.AccessMode.WRITE_ONLY)
        @NotBlank @Size(max = 128)
        String password) {

    /** Never let the password reach logs. */
    @Override
    public String toString() {
        return "LoginRequest[usernameOrEmail=" + usernameOrEmail + ", password=****]";
    }
}
