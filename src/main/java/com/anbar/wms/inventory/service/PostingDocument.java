package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.model.StockTransactionType;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.util.List;

/** A validated stock change: one ledger document with one or more lines, applied atomically. */
record PostingDocument(
        StockTransactionType type,
        String referenceNumber,
        String reason,
        Supplier supplier,
        List<Line> lines) {

    PostingDocument {
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A posting needs at least one line");
        }
    }

    record Line(MovementType movementType, Product product, StockBatch batch, BigDecimal quantity,
                Warehouse source, Warehouse destination, BigDecimal unitCost) {
    }
}
