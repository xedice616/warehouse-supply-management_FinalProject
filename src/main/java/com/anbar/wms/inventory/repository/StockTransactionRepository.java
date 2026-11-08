package com.anbar.wms.inventory.repository;

import com.anbar.wms.common.repository.AppendOnlyRepository;
import com.anbar.wms.inventory.entity.StockTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;

/** Append-only access to stock transactions. Saving a transaction also inserts its movements (cascade). */
public interface StockTransactionRepository extends AppendOnlyRepository<StockTransaction, Long> {

    Optional<StockTransaction> findByTransactionNumber(String transactionNumber);

    Optional<StockTransaction> findByIdempotencyKey(String idempotencyKey);

    boolean existsByReversalOfId(Long reversedTransactionId);

    @Query(value = "SELECT nextval('stock_movement_number_seq')", nativeQuery = true)
    long nextMovementNumber();
}
