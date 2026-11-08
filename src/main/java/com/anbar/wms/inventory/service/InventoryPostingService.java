package com.anbar.wms.inventory.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.InsufficientStockException;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.MovementLine;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.entity.StockTransaction;
import com.anbar.wms.inventory.repository.BalanceWriter;
import com.anbar.wms.inventory.repository.BalanceWriter.BatchStock;
import com.anbar.wms.inventory.repository.StockTransactionRepository;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single writer of stock. Applies a posting document to the balance projection and appends it to the ledger,
 * inside the caller's transaction (MANDATORY), so balances and their movement records commit or roll back together.
 */
@Service
public class InventoryPostingService {

    /** The global lock order for balance rows; FEFO allocation locks in the same order. */
    private static final Comparator<Leg> LOCK_ORDER = Comparator
            .comparing((Leg leg) -> leg.warehouse().getId())
            .thenComparing(leg -> leg.line().product().getId())
            .thenComparing(leg -> expiry(leg.line().batch()), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(leg -> batchNumber(leg.line().batch()), Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(leg -> batchId(leg.line().batch()), Comparator.nullsFirst(Comparator.naturalOrder()));

    private final BalanceWriter balances;
    private final StockTransactionRepository transactions;
    private final EntityManager entityManager;
    private final WmsProperties properties;
    private final Clock clock;

    public InventoryPostingService(BalanceWriter balances, StockTransactionRepository transactions,
                                   EntityManager entityManager, WmsProperties properties, Clock clock) {
        this.balances = balances;
        this.transactions = transactions;
        this.entityManager = entityManager;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    StockTransaction post(PostingDocument document, long actorUserId) {
        List<Leg> legs = new ArrayList<>();
        for (int i = 0; i < document.lines().size(); i++) {
            PostingDocument.Line line = document.lines().get(i);
            if (line.source() != null) {
                legs.add(new Leg(i, line, line.source(), true));
            }
            if (line.destination() != null) {
                legs.add(new Leg(i, line, line.destination(), false));
            }
        }
        legs.sort(LOCK_ORDER);

        Map<Integer, BigDecimal> sourceAfter = new LinkedHashMap<>();
        Map<Integer, BigDecimal> destinationAfter = new LinkedHashMap<>();
        for (Leg leg : legs) {
            if (leg.outgoing()) {
                sourceAfter.put(leg.index(), withdraw(leg));
            } else {
                destinationAfter.put(leg.index(), balances.increase(leg.warehouse().getId(),
                        leg.line().product().getId(), batchId(leg.line().batch()), leg.line().quantity()));
            }
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        StockTransaction transaction = new StockTransaction(movementNumber(now), document.type(),
                document.reason(), document.referenceNumber(), document.supplier(), null, null,
                entityManager.getReference(AppUser.class, actorUserId), now);
        for (int i = 0; i < document.lines().size(); i++) {
            PostingDocument.Line line = document.lines().get(i);
            transaction.addMovement(new MovementLine(line.movementType(), line.product(), line.batch(),
                    line.quantity(), line.source(), line.destination(), sourceAfter.get(i), destinationAfter.get(i),
                    line.unitCost()));
        }
        transactions.save(transaction);
        entityManager.flush();
        return transaction;
    }

    /**
     * FEFO: takes {@code quantity} from the product's batches in {@code warehouse}, earliest expiry first. The batch
     * rows are locked while choosing, so concurrent allocations queue instead of picking the same units. Expired
     * batches are skipped unless {@code includeExpired} (write-offs). For a transfer, pass the destination as
     * {@code otherWarehouse}: when it sorts first, its rows are locked first to keep the global lock order.
     *
     * @return batch id → quantity to take, in FEFO order
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Map<Long, BigDecimal> allocateFefo(Warehouse warehouse, Product product, BigDecimal quantity,
                                       boolean includeExpired, Warehouse otherWarehouse) {
        if (otherWarehouse != null && otherWarehouse.getId() < warehouse.getId()) {
            balances.lockProductRows(otherWarehouse.getId(), product.getId());
        }
        LocalDate today = LocalDate.now(clock.withZone(properties.businessTimezone()));
        List<BatchStock> candidates = balances.lockBatchesForAllocation(warehouse.getId(), product.getId(),
                includeExpired ? null : today);

        Map<Long, BigDecimal> allocation = new LinkedHashMap<>();
        BigDecimal remaining = quantity;
        for (BatchStock candidate : candidates) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = candidate.quantity().min(remaining);
            allocation.put(candidate.batchId(), take);
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            BigDecimal available = quantity.subtract(remaining);
            throw new InsufficientStockException(warehouse.getId(), product.getId(), available, quantity);
        }
        return allocation;
    }

    private BigDecimal withdraw(Leg leg) {
        PostingDocument.Line line = leg.line();
        long warehouseId = leg.warehouse().getId();
        long productId = line.product().getId();
        Long batchId = batchId(line.batch());
        return balances.decrease(warehouseId, productId, batchId, line.quantity())
                .orElseThrow(() -> new InsufficientStockException(warehouseId, productId,
                        balances.available(warehouseId, productId, batchId), line.quantity()));
    }

    private String movementNumber(Instant now) {
        int year = now.atZone(properties.businessTimezone()).getYear();
        return "SM-%d-%06d".formatted(year, transactions.nextMovementNumber());
    }

    private static Long batchId(StockBatch batch) {
        return batch == null ? null : batch.getId();
    }

    private static LocalDate expiry(StockBatch batch) {
        return batch == null ? null : batch.getExpiresOn();
    }

    private static String batchNumber(StockBatch batch) {
        return batch == null ? null : batch.getBatchNumber();
    }

    private record Leg(int index, PostingDocument.Line line, Warehouse warehouse, boolean outgoing) {
    }
}
