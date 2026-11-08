package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.service.ProductService;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.inventory.dto.StockMovementRequests;
import com.anbar.wms.inventory.dto.StockMovementRequests.AdjustmentDirection;
import com.anbar.wms.inventory.dto.StockMovementResponse;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.entity.StockTransaction;
import com.anbar.wms.inventory.mapper.StockMovementMapper;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.model.StockTransactionType;
import com.anbar.wms.inventory.service.BatchResolver.InboundBatch;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.supplier.service.SupplierService;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.service.WarehouseService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock movement use cases, and the only entry point for other modules that change stock (purchase receipts,
 * audit adjustments). Each public method runs in one database transaction: warehouse locks, rule checks, balance
 * updates and the ledger record commit together or not at all.
 *
 * <p>Batch-tracked products: an explicitly named batch is used as is; otherwise FEFO picks the batches with the
 * earliest expiry, skipping expired ones (write-offs may take expired stock), possibly splitting one request into
 * several ledger lines of the same document.
 */
@Service
public class StockMovementService {

    private final InventoryPostingService posting;
    private final ProductService products;
    private final WarehouseService warehouses;
    private final SupplierService suppliers;
    private final BatchResolver batches;
    private final StockMovementMapper mapper;

    public StockMovementService(InventoryPostingService posting, ProductService products, WarehouseService warehouses,
                                SupplierService suppliers, BatchResolver batches, StockMovementMapper mapper) {
        this.posting = posting;
        this.products = products;
        this.warehouses = warehouses;
        this.suppliers = suppliers;
        this.batches = batches;
        this.mapper = mapper;
    }

    // ------------------------------------------------------------------ API use cases

    @Transactional
    public StockMovementResponse inbound(StockMovementRequests.Inbound request, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.lockForStockOperation(request.warehouseId(), actor.warehouseScope());
        Product product = activeProduct(request.productId(), request.quantity());
        Supplier supplier = request.supplierId() == null ? null : suppliers.requireActive(request.supplierId());
        BigDecimal unitCost = request.unitCost() != null ? request.unitCost() : product.getPurchasePrice();
        StockBatch batch = batches.forInbound(product, new InboundBatch(request.batchNumber(), request.expiresOn(),
                request.manufacturedOn(), supplier, unitCost, null));
        return post(StockTransactionType.INBOUND, request.referenceNumber(), request.reason(), supplier, actor,
                List.of(new PostingDocument.Line(MovementType.INBOUND, product, batch, request.quantity(), null,
                        warehouse, unitCost)));
    }

    @Transactional
    public StockMovementResponse outbound(StockMovementRequests.Outbound request, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.lockForStockOperation(request.warehouseId(), actor.warehouseScope());
        Product product = activeProduct(request.productId(), request.quantity());
        return post(StockTransactionType.OUTBOUND, request.referenceNumber(), request.reason(), null, actor,
                outgoingLines(MovementType.OUTBOUND, product, request.batchNumber(), request.quantity(), warehouse,
                        null, false));
    }

    @Transactional
    public StockMovementResponse transfer(StockMovementRequests.Transfer request, AuthenticatedUser actor) {
        if (request.sourceWarehouseId().equals(request.destinationWarehouseId())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "A warehouse cannot transfer stock to itself",
                    Map.of("field", "destinationWarehouseId"));
        }
        WarehouseScope scope = actor.warehouseScope();
        long first = Math.min(request.sourceWarehouseId(), request.destinationWarehouseId());
        long second = Math.max(request.sourceWarehouseId(), request.destinationWarehouseId());
        Warehouse lockedFirst = warehouses.lockForStockOperation(first, scope);
        Warehouse lockedSecond = warehouses.lockForStockOperation(second, scope);
        Warehouse source = lockedFirst.getId().equals(request.sourceWarehouseId()) ? lockedFirst : lockedSecond;
        Warehouse destination = source == lockedFirst ? lockedSecond : lockedFirst;

        Product product = activeProduct(request.productId(), request.quantity());
        return post(StockTransactionType.TRANSFER, request.referenceNumber(), request.reason(), null, actor,
                outgoingLines(MovementType.TRANSFER, product, request.batchNumber(), request.quantity(), source,
                        destination, false));
    }

    @Transactional
    public StockMovementResponse adjust(StockMovementRequests.Adjustment request, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.lockForStockOperation(request.warehouseId(), actor.warehouseScope());
        Product product = activeProduct(request.productId(), request.quantity());
        if (request.direction() == AdjustmentDirection.INCREASE) {
            StockBatch batch = batches.forInbound(product, new InboundBatch(request.batchNumber(), request.expiresOn(),
                    null, null, null, null));
            return post(StockTransactionType.ADJUSTMENT, request.referenceNumber(), request.reason(), null, actor,
                    List.of(new PostingDocument.Line(MovementType.ADJUSTMENT, product, batch, request.quantity(), null,
                            warehouse, null)));
        }
        return post(StockTransactionType.ADJUSTMENT, request.referenceNumber(), request.reason(), null, actor,
                outgoingLines(MovementType.ADJUSTMENT, product, request.batchNumber(), request.quantity(), warehouse,
                        null, true));
    }

    @Transactional
    public StockMovementResponse writeOff(StockMovementRequests.WriteOff request, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.lockForStockOperation(request.warehouseId(), actor.warehouseScope());
        Product product = activeProduct(request.productId(), request.quantity());
        return post(StockTransactionType.WRITE_OFF, request.referenceNumber(), request.reason(), null, actor,
                outgoingLines(MovementType.WRITE_OFF, product, request.batchNumber(), request.quantity(), warehouse,
                        null, true));
    }

    // ------------------------------------------------------------------ entry points for other modules

    /** One line of goods received against a purchase order. */
    public record ReceiptLine(long productId, BigDecimal quantity, BigDecimal unitCost, String batchNumber,
                              LocalDate expiresOn, LocalDate producedOn, Long purchaseOrderLineId) {
    }

    /** Receives purchased goods: one INBOUND document with a line per received item. Joins the caller's transaction. */
    @Transactional
    public StockTransaction receivePurchase(long warehouseId, Supplier supplier, String referenceNumber,
                                            List<ReceiptLine> lines, AuthenticatedUser actor) {
        return postInbound(warehouseId, supplier, referenceNumber, "Purchase order receipt", lines, actor);
    }

    /**
     * Posts one INBOUND document for a CSV bulk import (no supplier, no purchase order). Goes through the same
     * posting path as every other inbound, so balances, batches and the ledger stay consistent. Joins the
     * caller's transaction, so the whole import commits or rolls back together.
     */
    @Transactional
    public StockTransaction importInbound(long warehouseId, String referenceNumber, List<ReceiptLine> lines,
                                          AuthenticatedUser actor) {
        return postInbound(warehouseId, null, referenceNumber, "CSV import", lines, actor);
    }

    private StockTransaction postInbound(long warehouseId, Supplier supplier, String referenceNumber, String reason,
                                         List<ReceiptLine> lines, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.lockForStockOperation(warehouseId, actor.warehouseScope());
        List<PostingDocument.Line> postingLines = new ArrayList<>();
        for (ReceiptLine line : lines) {
            Product product = activeProduct(line.productId(), line.quantity());
            StockBatch batch = batches.forInbound(product, new InboundBatch(line.batchNumber(), line.expiresOn(),
                    line.producedOn(), supplier, line.unitCost(), line.purchaseOrderLineId()));
            postingLines.add(new PostingDocument.Line(MovementType.INBOUND, product, batch, line.quantity(), null,
                    warehouse, line.unitCost()));
        }
        return posting.post(new PostingDocument(StockTransactionType.INBOUND, referenceNumber, reason, supplier,
                postingLines), actor.id());
    }

    /** A counted difference: positive adds stock, negative removes it. */
    public record AdjustmentLine(long productId, Long batchId, BigDecimal difference) {
    }

    /**
     * Posts audit differences as one ADJUSTMENT document. Decreases are checked against the current balance like
     * any withdrawal, so an audit can't push stock below zero. Joins the caller's transaction.
     */
    @Transactional
    public StockTransaction postAdjustments(long warehouseId, String referenceNumber, String reason,
                                            List<AdjustmentLine> lines, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.lockForStockOperation(warehouseId, actor.warehouseScope());
        List<PostingDocument.Line> postingLines = new ArrayList<>();
        for (AdjustmentLine line : lines) {
            Product product = products.require(line.productId());
            StockBatch batch = line.batchId() == null ? null : batches.byId(line.batchId());
            boolean increase = line.difference().signum() > 0;
            postingLines.add(new PostingDocument.Line(MovementType.ADJUSTMENT, product, batch, line.difference().abs(),
                    increase ? null : warehouse, increase ? warehouse : null, null));
        }
        return posting.post(new PostingDocument(StockTransactionType.ADJUSTMENT, referenceNumber, reason, null,
                postingLines), actor.id());
    }

    // ------------------------------------------------------------------ helpers

    private List<PostingDocument.Line> outgoingLines(MovementType type, Product product, String batchNumber,
                                                     BigDecimal quantity, Warehouse source, Warehouse destination,
                                                     boolean expiredAllowed) {
        if (!BatchResolver.needsAllocation(product, batchNumber)) {
            StockBatch batch = batches.existing(product, batchNumber, expiredAllowed);
            return List.of(new PostingDocument.Line(type, product, batch, quantity, source, destination, null));
        }
        List<PostingDocument.Line> lines = new ArrayList<>();
        posting.allocateFefo(source, product, quantity, expiredAllowed, destination).forEach((batchId, taken) ->
                lines.add(new PostingDocument.Line(type, product, batches.byId(batchId), taken, source, destination, null)));
        return lines;
    }

    private Product activeProduct(long productId, BigDecimal quantity) {
        Product product = products.requireActive(productId);
        if (!product.getUnitOfMeasure().accepts(quantity)) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY_SCALE,
                    "Quantities of %s allow at most %d decimal places".formatted(
                            product.getUnitOfMeasure().getCode(), product.getUnitOfMeasure().getDecimalScale()),
                    Map.of("field", "quantity"));
        }
        return product;
    }

    private StockMovementResponse post(StockTransactionType type, String reference, String reason, Supplier supplier,
                                       AuthenticatedUser actor, List<PostingDocument.Line> lines) {
        StockTransaction transaction = posting.post(new PostingDocument(type, blankToNull(reference),
                blankToNull(reason), supplier, lines), actor.id());
        return mapper.toPostingResponse(transaction);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
