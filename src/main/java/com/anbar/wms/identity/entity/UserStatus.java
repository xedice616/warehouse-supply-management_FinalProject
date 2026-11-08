package com.anbar.wms.identity.entity;

/**
 * Account status. Temporary lockout after failed logins is tracked separately in {@code lockedUntil},
 * so it expires on its own without an administrator.
 */
public enum UserStatus {
    ACTIVE,
    DISABLED
}
