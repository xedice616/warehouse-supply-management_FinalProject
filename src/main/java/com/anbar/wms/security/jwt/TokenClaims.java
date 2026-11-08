package com.anbar.wms.security.jwt;

import java.time.Instant;
import java.util.List;

/**
 * The verified contents of an access token. {@code username} and {@code roles} are informational; authorization
 * uses the user's current state from the database, looked up by {@code userId}.
 */
public record TokenClaims(long userId, String username, List<String> roles, Instant expiresAt) {

    public TokenClaims {
        roles = List.copyOf(roles);
    }
}
