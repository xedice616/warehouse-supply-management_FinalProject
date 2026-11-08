package com.anbar.wms.security.jwt;

import java.time.Instant;

/** A signed access token and the moment it stops being accepted. */
public record AccessToken(String value, Instant expiresAt) {

    @Override
    public String toString() {
        return "AccessToken[value=****, expiresAt=" + expiresAt + "]";
    }
}
