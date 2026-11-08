package com.anbar.wms.audit.repository;

import com.anbar.wms.audit.entity.InventoryAudit;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.security.WarehouseScope;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class InventoryAuditSpecifications {

    private InventoryAuditSpecifications() {
    }

    public static Specification<InventoryAudit> matching(WarehouseScope scope, String auditNumber, Long warehouseId,
                                                         AuditStatus status, Instant from, Instant to) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (!scope.unrestricted()) {
                predicates.add(scope.warehouseIds().isEmpty()
                        ? cb.disjunction() : root.get("warehouse").get("id").in(scope.warehouseIds()));
            }
            if (auditNumber != null && !auditNumber.isBlank()) {
                String prefix = auditNumber.trim().toLowerCase(Locale.ROOT)
                        .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                predicates.add(cb.like(cb.lower(root.get("auditNumber")), prefix, '\\'));
            }
            if (warehouseId != null) {
                predicates.add(cb.equal(root.get("warehouse").get("id"), warehouseId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThan(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}
