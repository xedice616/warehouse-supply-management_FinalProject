package com.anbar.wms.purchasing.repository;

import com.anbar.wms.purchasing.entity.PurchaseOrder;
import com.anbar.wms.purchasing.model.PurchaseOrderStatus;
import com.anbar.wms.security.WarehouseScope;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

public final class PurchaseOrderSpecifications {

    private PurchaseOrderSpecifications() {
    }

    public static Specification<PurchaseOrder> matching(WarehouseScope scope, String orderNumber, Long supplierId,
                                                        Long warehouseId, PurchaseOrderStatus status,
                                                        boolean outstandingOnly, LocalDate from, LocalDate to) {
        List<Specification<PurchaseOrder>> filters = new ArrayList<>();
        if (!scope.unrestricted()) {
            filters.add(scope.warehouseIds().isEmpty()
                    ? (root, query, cb) -> cb.disjunction()
                    : (root, query, cb) -> root.get("warehouse").get("id").in(scope.warehouseIds()));
        }
        if (orderNumber != null && !orderNumber.isBlank()) {
            String prefix = orderNumber.trim().toUpperCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
            filters.add((root, query, cb) -> cb.like(root.get("poNumber"), prefix));
        }
        if (supplierId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("supplier").get("id"), supplierId));
        }
        if (warehouseId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("warehouse").get("id"), warehouseId));
        }
        if (status != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (outstandingOnly) {
            filters.add((root, query, cb) -> root.get("status")
                    .in(PurchaseOrderStatus.APPROVED, PurchaseOrderStatus.PARTIALLY_RECEIVED));
        }
        if (from != null) {
            filters.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("orderDate"), from));
        }
        if (to != null) {
            filters.add((root, query, cb) -> cb.lessThanOrEqualTo(root.get("orderDate"), to));
        }
        return Specification.allOf(filters);
    }
}
