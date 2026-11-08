package com.anbar.wms.inventory.repository;

import com.anbar.wms.inventory.entity.StockBatch;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    Optional<StockBatch> findByProductIdAndBatchNumber(Long productId, String batchNumber);
}
