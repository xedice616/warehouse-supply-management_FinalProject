package com.anbar.wms.warehouse.repository;

import com.anbar.wms.common.repository.SearchPatterns;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.entity.WarehouseStatus;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class WarehouseSpecifications {

    private WarehouseSpecifications() {
    }

    public static Specification<Warehouse> warehouses(WarehouseScope scope, String search, Boolean active) {
        List<Specification<Warehouse>> filters = new ArrayList<>();
        if (!scope.unrestricted()) {
            filters.add(scope.warehouseIds().isEmpty()
                    ? (root, query, cb) -> cb.disjunction()
                    : (root, query, cb) -> root.get("id").in(scope.warehouseIds()));
        }
        if (!SearchPatterns.isBlank(search)) {
            String pattern = SearchPatterns.lowerPrefix(search);
            filters.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("code")), pattern, SearchPatterns.ESCAPE),
                    cb.like(cb.lower(root.get("name")), pattern, SearchPatterns.ESCAPE)));
        }
        if (active != null) {
            WarehouseStatus status = active ? WarehouseStatus.ACTIVE : WarehouseStatus.INACTIVE;
            filters.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        return Specification.allOf(filters);
    }
}
