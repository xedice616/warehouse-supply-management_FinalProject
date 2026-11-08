package com.anbar.wms.catalog.repository;

import com.anbar.wms.catalog.entity.UnitOfMeasure;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, Long>,
        JpaSpecificationExecutor<UnitOfMeasure> {

    Optional<UnitOfMeasure> findByCode(String code);

    boolean existsByCode(String code);
}
