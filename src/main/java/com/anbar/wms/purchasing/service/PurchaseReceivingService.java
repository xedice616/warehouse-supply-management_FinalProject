package com.anbar.wms.purchasing.service;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockTransaction;
import com.anbar.wms.inventory.mapper.StockMovementMapper;
import com.anbar.wms.inventory.service.StockMovementService;
import com.anbar.wms.inventory.service.StockMovementService.ReceiptLine;
import com.anbar.wms.purchasing.dto.PurchaseOrderRequests;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.ReceiptResponse;
import com.anbar.wms.purchasing.entity.GoodsReceipt;
import com.anbar.wms.purchasing.entity.PurchaseOrder;
import com.anbar.wms.purchasing.entity.PurchaseOrderLine;
import com.anbar.wms.purchasing.repository.GoodsReceiptRepository;
import com.anbar.wms.purchasing.repository.PurchaseOrderRepository;
import com.anbar.wms.security.AuthenticatedUser;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receives goods against an approved purchase order, through the stock movement service (one INBOUND document per
 * receipt). Idempotent: the client sends an {@code Idempotency-Key}; repeating a request with the same key returns
 * the original receipt instead of receiving again.
 *
 * <p>Concurrency: the order row is locked first ({@code FOR UPDATE}), and the key is looked up only after that lock
 * is held, so two identical requests can't both pass the "not seen yet" check, and two different receipts for the
 * same order can't both over-receive a line. The unique key constraint is the backstop.
 */
@Service
public class PurchaseReceivingService {

    private static final int MAX_KEY_LENGTH = 100;

    private final PurchaseOrderRepository orders;
    private final GoodsReceiptRepository receipts;
    private final StockMovementService stockMovements;
    private final StockMovementMapper movementMapper;
    private final EntityManager entityManager;
    private final Clock clock;

    public PurchaseReceivingService(PurchaseOrderRepository orders, GoodsReceiptRepository receipts,
                                    StockMovementService stockMovements, StockMovementMapper movementMapper,
                                    EntityManager entityManager, Clock clock) {
        this.orders = orders;
        this.receipts = receipts;
        this.stockMovements = stockMovements;
        this.movementMapper = movementMapper;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public ReceiptResponse receive(long orderId, String idempotencyKey, PurchaseOrderRequests.Receipt request,
                                   AuthenticatedUser actor) {
        String key = validKey(idempotencyKey);
        PurchaseOrder order = orders.lockById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException(PurchaseOrderService.RESOURCE, orderId));
        PurchaseOrderService.requireVisible(order, actor.warehouseScope());

        Optional<GoodsReceipt> earlier = receipts.findByIdempotencyKey(key);
        if (earlier.isPresent()) {
            return replay(earlier.get(), order);
        }
        if (!order.getStatus().isReceivable()) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Only APPROVED or PARTIALLY_RECEIVED orders can receive goods; this one is " + order.getStatus(),
                    Map.of("status", order.getStatus().name()));
        }

        List<ReceiptLine> stockLines = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (PurchaseOrderRequests.ReceiptItem item : request.items()) {
            if (!seen.add(item.itemId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Each item may appear only once per receipt",
                        Map.of("field", "items", "itemId", item.itemId()));
            }
            PurchaseOrderLine line = order.getLines().stream()
                    .filter(l -> l.getId().equals(item.itemId()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Purchase order item", item.itemId()));
            line.receive(item.quantity());
            stockLines.add(new ReceiptLine(line.getProduct().getId(), item.quantity(), line.getUnitPrice(),
                    item.batchNumber(), item.expirationDate(), item.productionDate(), line.getId()));
        }

        StockTransaction transaction = stockMovements.receivePurchase(order.getWarehouse().getId(),
                order.getSupplier(), order.getPoNumber(), stockLines, actor);
        order.refreshReceivingStatus();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        GoodsReceipt receipt;
        try {
            receipt = receipts.saveAndFlush(new GoodsReceipt(order, key, transaction,
                    entityManager.getReference(AppUser.class, actor.id()), now));
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.violates(ex, "uq_goods_receipt_idempotency_key")) {
                throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            throw ex;
        }
        return new ReceiptResponse(receipt.getId(), order.getPoNumber(), order.getStatus().name(), false,
                actor.username(), now, movementMapper.toPostingResponse(transaction));
    }

    private ReceiptResponse replay(GoodsReceipt receipt, PurchaseOrder order) {
        if (!receipt.getPurchaseOrder().getId().equals(order.getId())) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSED);
        }
        return new ReceiptResponse(receipt.getId(), order.getPoNumber(), order.getStatus().name(), true,
                receipt.getReceivedBy().getUsername(), receipt.getReceivedAt(),
                movementMapper.toPostingResponse(receipt.getStockTransaction()));
    }

    private static String validKey(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        return key.trim();
    }
}
