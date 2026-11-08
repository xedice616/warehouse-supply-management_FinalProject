package com.anbar.wms.inventory.repository;

import com.anbar.wms.common.repository.ReadOnlyRepository;
import com.anbar.wms.inventory.entity.StockMovement;
import java.util.List;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Read access to the ledger. Movements are only ever inserted through their {@code StockTransaction}. */
public interface StockMovementRepository extends ReadOnlyRepository<StockMovement, Long>,
        JpaSpecificationExecutor<StockMovement> {

    List<StockMovement> findByTransactionIdOrderByLineNoAsc(Long transactionId);

    boolean existsByProductId(Long productId);
}
