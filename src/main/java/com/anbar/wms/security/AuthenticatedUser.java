package com.anbar.wms.security;

import java.util.Collection;
import java.util.Set;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/**
 * The principal of an authenticated request: just enough to authorize and audit, nothing sensitive.
 * Controllers receive it with {@code @AuthenticationPrincipal AuthenticatedUser user}.
 */
public record AuthenticatedUser(long id, String username, Set<String> roles, Set<Long> warehouseIds)
        implements AuthenticatedPrincipal {

    public AuthenticatedUser {
        roles = Set.copyOf(roles);
        warehouseIds = Set.copyOf(warehouseIds);
    }

    @Override
    public String getName() {
        return username;
    }

    public Collection<? extends GrantedAuthority> authorities() {
        return roles.stream()
                .sorted()
                .map(role -> new SimpleGrantedAuthority(RoleCode.AUTHORITY_PREFIX + role))
                .toList();
    }

    public boolean hasRole(RoleCode role) {
        return roles.contains(role.name());
    }

    public WarehouseScope warehouseScope() {
        return hasRole(RoleCode.ADMIN) ? WarehouseScope.all() : WarehouseScope.only(warehouseIds);
    }
}
