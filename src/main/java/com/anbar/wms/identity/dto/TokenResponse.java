package com.anbar.wms.identity.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

/**
 * A successful login. Send the token as {@code Authorization: Bearer <accessToken>}.
 *
 * @param expiresIn seconds until the token expires
 */
public record TokenResponse(
        @Schema(example = "eyJhbGciOiJIUzI1NiJ9...") String accessToken,
        @Schema(example = "Bearer") String tokenType,
        @Schema(example = "900") long expiresIn,
        Instant expiresAt) {

    public static final String BEARER = "Bearer";

    @Override
    public String toString() {
        return "TokenResponse[accessToken=****, tokenType=" + tokenType + ", expiresAt=" + expiresAt + "]";
    }
}
