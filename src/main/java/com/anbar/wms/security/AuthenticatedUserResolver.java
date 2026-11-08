package com.anbar.wms.security;

import java.util.Optional;

/**
 * Loads the current state of the user a valid token belongs to. Implemented by the identity module, so the
 * security layer doesn't depend on user persistence.
 *
 * <p>Resolving on every request (one indexed lookup) means a disabled account or a role change takes effect
 * immediately instead of when the token expires.
 */
public interface AuthenticatedUserResolver {

    /** The principal for an active user, or empty if the user no longer exists or is disabled. */
    Optional<AuthenticatedUser> resolve(long userId);
}
