package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.repository.StockSnapshotQuery;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** System quantities for physical counts. */
@Service
public class StockSnapshotService {

    public record StockedRow(long productId, Long batchId, BigDecimal quantity) {
    }

    private final StockSnapshotQuery query;
    private final BatchResolver batches;

    public StockSnapshotService(StockSnapshotQuery query, BatchResolver batches) {
        this.query = query;
        this.batches = batches;
    }

    /** The current balance, held steady until the caller commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal lockQuantity(long warehouseId, long productId, Long batchId) {
        return query.lockQuantity(warehouseId, productId, batchId);
    }

    @Transactional(readOnly = true)
    public List<StockedRow> stocked(long warehouseId, Long productId) {
        return query.stocked(warehouseId, productId).stream()
                .map(row -> new StockedRow(row.productId(), row.batchId(), row.quantity()))
                .toList();
    }

    /** A named lot of the product; expired lots included, since they are counted too. */
    @Transactional(readOnly = true)
    public StockBatch batch(Product product, String batchNumber) {
        return batches.existing(product, batchNumber, true);
    }

    @Transactional(readOnly = true)
    public StockBatch batch(long batchId) {
        return batches.byId(batchId);
    }
}
