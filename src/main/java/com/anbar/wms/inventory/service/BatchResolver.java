package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.repository.StockBatchRepository;
import com.anbar.wms.supplier.entity.Supplier;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Batch rules of a product. Batch numbers are unique per product; the same lot may sit in several warehouses,
 * with its quantity per warehouse kept in the balance projection.
 */
@Component
class BatchResolver {

    private final StockBatchRepository batches;
    private final WmsProperties properties;
    private final Clock clock;

    BatchResolver(StockBatchRepository batches, WmsProperties properties, Clock clock) {
        this.batches = batches;
        this.properties = properties;
        this.clock = clock;
    }

    record InboundBatch(String batchNumber, LocalDate expiresOn, LocalDate producedOn, Supplier supplier,
                        BigDecimal unitCost, Long purchaseOrderLineId) {
    }

    /** For stock coming in: an existing lot of the product, or a new one with the given dates and cost. */
    StockBatch forInbound(Product product, InboundBatch request) {
        if (!requireBatchRule(product, request.batchNumber(), true)) {
            return null;
        }
        String number = request.batchNumber().trim();
        StockBatch batch = batches.findByProductIdAndBatchNumber(product.getId(), number)
                .orElseGet(() -> create(product, number, request));
        if (batch.isExpiredOn(today())) {
            throw new BusinessException(ErrorCode.BATCH_EXPIRED,
                    "Batch %s expired on %s; expired goods can't be received".formatted(number, batch.getExpiresOn()));
        }
        return batch;
    }

    /** For stock going out with an explicitly named lot; expired lots only when {@code expiredAllowed}. */
    StockBatch existing(Product product, String batchNumber, boolean expiredAllowed) {
        if (!requireBatchRule(product, batchNumber, true)) {
            return null;
        }
        StockBatch batch = batches.findByProductIdAndBatchNumber(product.getId(), batchNumber.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Batch", batchNumber.trim()));
        if (!expiredAllowed && batch.isExpiredOn(today())) {
            throw new BusinessException(ErrorCode.BATCH_EXPIRED,
                    "Batch %s expired on %s; write it off instead".formatted(batch.getBatchNumber(), batch.getExpiresOn()),
                    Map.of("batchNumber", batch.getBatchNumber()));
        }
        return batch;
    }

    /** True when a batch-tracked product has no batch named, so FEFO should choose. */
    static boolean needsAllocation(Product product, String batchNumber) {
        return product.isBatchTracked() && (batchNumber == null || batchNumber.isBlank());
    }

    StockBatch byId(long id) {
        return batches.findById(id).orElseThrow(() -> new ResourceNotFoundException("Batch", id));
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(properties.businessTimezone()));
    }

    private StockBatch create(Product product, String number, InboundBatch request) {
        if (product.isExpiryTracked() && request.expiresOn() == null) {
            throw new BusinessException(ErrorCode.EXPIRY_DATE_REQUIRED);
        }
        if (request.producedOn() != null && request.expiresOn() != null
                && !request.expiresOn().isAfter(request.producedOn())) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The expiration date must be after the production date", Map.of("field", "expiresOn"));
        }
        // Flushed now: the balance update that follows is plain SQL and must see the new batch row.
        return batches.saveAndFlush(new StockBatch(product, number, request.producedOn(), request.expiresOn(),
                request.supplier(), request.unitCost(), request.purchaseOrderLineId()));
    }

    private static boolean requireBatchRule(Product product, String batchNumber, boolean required) {
        boolean given = batchNumber != null && !batchNumber.isBlank();
        if (product.isBatchTracked() && !given && required) {
            throw new BusinessException(ErrorCode.BATCH_REQUIRED);
        }
        if (!product.isBatchTracked() && given) {
            throw new BusinessException(ErrorCode.BATCH_NOT_ALLOWED);
        }
        return given;
    }
}
