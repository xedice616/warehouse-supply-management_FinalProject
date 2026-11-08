package com.anbar.wms.security;

import java.util.Set;

/**
 * Which warehouses a user may see stock and details for. Administrators see all; everyone else sees only the
 * warehouses assigned to them. Out-of-scope warehouses are reported as not found, never as forbidden.
 */
public record WarehouseScope(boolean unrestricted, Set<Long> warehouseIds) {

    public WarehouseScope {
        warehouseIds = Set.copyOf(warehouseIds);
    }

    public static WarehouseScope all() {
        return new WarehouseScope(true, Set.of());
    }

    public static WarehouseScope only(Set<Long> warehouseIds) {
        return new WarehouseScope(false, warehouseIds);
    }

    public boolean allows(long warehouseId) {
        return unrestricted || warehouseIds.contains(warehouseId);
    }
}
