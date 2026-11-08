package com.anbar.wms.warehouse.repository;

import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WarehouseRepository extends JpaRepository<Warehouse, Long>, JpaSpecificationExecutor<Warehouse> {

    Optional<Warehouse> findByCode(String code);

    boolean existsByCode(String code);

    /** {@code SELECT ... FOR UPDATE}: taken by status changes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> lockById(@Param("id") Long id);

    /** {@code SELECT ... FOR SHARE}: taken by stock postings; postings don't block each other, only status changes. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select w from Warehouse w where w.id = :id")
    Optional<Warehouse> lockSharedById(@Param("id") Long id);
}
