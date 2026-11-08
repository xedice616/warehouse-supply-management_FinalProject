package com.anbar.wms.audit.repository;

import com.anbar.wms.audit.entity.InventoryAudit;
import com.anbar.wms.audit.model.AuditScope;
import com.anbar.wms.audit.model.AuditStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryAuditRepository extends JpaRepository<InventoryAudit, Long>,
        JpaSpecificationExecutor<InventoryAudit> {

    Optional<InventoryAudit> findByAuditNumber(String auditNumber);

    boolean existsByWarehouseIdAndScopeAndStatusIn(Long warehouseId, AuditScope scope, Collection<AuditStatus> statuses);

    @EntityGraph(attributePaths = {"warehouse", "items", "items.product", "items.product.unitOfMeasure", "items.batch"})
    Optional<InventoryAudit> findWithItemsById(Long id);

    /** Serializes workflow changes on one audit, so it can't be completed twice by concurrent requests. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from InventoryAudit a where a.id = :id")
    Optional<InventoryAudit> lockById(@Param("id") Long id);

    @Query(value = "SELECT nextval('inventory_audit_number_seq')", nativeQuery = true)
    long nextAuditNumber();
}
