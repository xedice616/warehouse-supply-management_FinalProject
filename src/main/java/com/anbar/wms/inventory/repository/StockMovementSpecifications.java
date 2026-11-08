package com.anbar.wms.inventory.repository;

import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.entity.StockMovement;
import com.anbar.wms.security.WarehouseScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class StockMovementSpecifications {

    private StockMovementSpecifications() {
    }

    public static Specification<StockMovement> matching(WarehouseScope scope, Long productId, Long batchId, Long warehouseId,
                                                        MovementType type, Long supplierId, String referenceNumber,
                                                        Instant fromInclusive, Instant toExclusive) {
        List<Specification<StockMovement>> filters = new ArrayList<>();
        if (!scope.unrestricted()) {
            filters.add(scope.warehouseIds().isEmpty()
                    ? (root, query, cb) -> cb.disjunction()
                    : (root, query, cb) -> cb.or(
                            root.get("sourceWarehouse").get("id").in(scope.warehouseIds()),
                            root.get("destinationWarehouse").get("id").in(scope.warehouseIds())));
        }
        if (productId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("product").get("id"), productId));
        }
        if (batchId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("batch").get("id"), batchId));
        }
        if (warehouseId != null) {
            filters.add((root, query, cb) -> cb.or(
                    cb.equal(root.get("sourceWarehouse").get("id"), warehouseId),
                    cb.equal(root.get("destinationWarehouse").get("id"), warehouseId)));
        }
        if (type != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("movementType"), type));
        }
        if (supplierId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("transaction").get("supplier").get("id"), supplierId));
        }
        if (referenceNumber != null && !referenceNumber.isBlank()) {
            filters.add((root, query, cb) ->
                    cb.equal(root.get("transaction").get("referenceNumber"), referenceNumber.trim()));
        }
        if (fromInclusive != null) {
            filters.add((root, query, cb) -> cb.greaterThanOrEqualTo(root.get("performedAt"), fromInclusive));
        }
        if (toExclusive != null) {
            filters.add((root, query, cb) -> cb.lessThan(root.get("performedAt"), toExclusive));
        }
        return Specification.allOf(filters);
    }
}
