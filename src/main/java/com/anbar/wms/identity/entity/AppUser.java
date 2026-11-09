package com.anbar.wms.identity.entity;

import com.anbar.wms.common.entity.AuditableEntity;
import com.anbar.wms.security.RoleCode;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A person who signs in to the system. Never hard-deleted, because stock movements reference users forever;
 * accounts are disabled instead. Usernames and emails are normalized to lower case so uniqueness is case-insensitive.
 *
 * <p>Login counters ({@code failedLoginAttempts}, {@code lockedUntil}, {@code lastLoginAt}) are written by atomic
 * repository updates rather than through this entity, so concurrent login attempts can't lose increments.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AppUser extends AuditableEntity {

    @Column(name = "username", nullable = false, length = 50)
    private String username;

    @Column(name = "email", nullable = false, length = 254)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @ManyToMany
    @JoinTable(name = "user_role",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    /** Data scope: warehouses a non-admin user may operate on. */
    @ManyToMany
    @JoinTable(name = "user_warehouse_access",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "warehouse_id"))
    private Set<Warehouse> warehouses = new HashSet<>();

    /**
     * @param passwordHash an already-encoded password (BCrypt); never a raw password
     */
    public AppUser(String username, String email, String passwordHash, String fullName) {
        this.username = normalizeUsername(Objects.requireNonNull(username, "username"));
        this.email = normalizeEmail(Objects.requireNonNull(email, "email"));
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.fullName = Objects.requireNonNull(fullName, "fullName").trim();
        this.status = UserStatus.ACTIVE;
    }

    public static String normalizeUsername(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }

    public static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    /** Replaces all role assignments. A user always keeps at least one role. */
    public void replaceRoles(Collection<Role> newRoles) {
        if (newRoles.isEmpty()) {
            throw new IllegalArgumentException("A user must have at least one role");
        }
        roles.clear();
        roles.addAll(newRoles);
    }

    public void grantWarehouse(Warehouse warehouse) {
        warehouses.add(warehouse);
    }

    public void revokeWarehouse(Warehouse warehouse) {
        warehouses.remove(warehouse);
    }

    public void replaceWarehouses(Collection<Warehouse> newWarehouses) {
        warehouses.clear();
        warehouses.addAll(newWarehouses);
    }

    public void enable() {
        this.status = UserStatus.ACTIVE;
    }

    /** A disabled account cannot log in, and its existing tokens are rejected on the next request. */
    public void disable() {
        this.status = UserStatus.DISABLED;
    }

    /** Clears a temporary lockout and the failed-login counter (administrator action). */
    public void unlock() {
        this.failedLoginAttempts = 0;
        this.lockedUntil = null;
    }

    /** @param newPasswordHash an already-encoded password (BCrypt); never a raw password */
    public void changePassword(String newPasswordHash) {
        this.passwordHash = Objects.requireNonNull(newPasswordHash, "newPasswordHash");
    }

    public boolean hasRole(RoleCode code) {
        return roles.stream().anyMatch(role -> role.getCode().equals(code.name()));
    }

    public Set<String> roleCodes() {
        return roles.stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /** True while a temporary lockout after repeated failed logins is in effect. */
    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    public Set<Role> getRoles() {
        return Collections.unmodifiableSet(roles);
    }

    public Set<Warehouse> getWarehouses() {
        return Collections.unmodifiableSet(warehouses);
    }
}
