package com.anbar.wms.security;

/**
 * The roles that exist in the {@code role} table (migration V9). Spring Security authorities are
 * {@code ROLE_<code>}, so {@code @PreAuthorize("hasRole('ADMIN')")} matches {@link #ADMIN}.
 */
public enum RoleCode {

    /** Full system administration: users and roles, master data, purchasing, inventory and all reports. */
    ADMIN,

    /** Operates assigned warehouses: stock movements, receiving purchase orders, inventory audits, warehouse reports. */
    WAREHOUSE_MANAGER,

    /** Read-only access to permitted products, stock and warehouses. The role every self-registered user gets. */
    USER;

    public static final String AUTHORITY_PREFIX = "ROLE_";

    public String authority() {
        return AUTHORITY_PREFIX + name();
    }
}
