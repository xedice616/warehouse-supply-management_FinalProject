package com.anbar.wms.audit.service;

import com.anbar.wms.audit.dto.InventoryAuditRequests;
import com.anbar.wms.audit.dto.InventoryAuditResponses.AuditResponse;
import com.anbar.wms.audit.entity.InventoryAudit;
import com.anbar.wms.audit.entity.InventoryAuditItem;
import com.anbar.wms.audit.mapper.InventoryAuditMapper;
import com.anbar.wms.audit.model.AuditScope;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.audit.repository.InventoryAuditQuery;
import com.anbar.wms.audit.repository.InventoryAuditRepository;
import com.anbar.wms.audit.repository.InventoryAuditSpecifications;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.service.ProductService;
import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ConstraintViolations;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.entity.StockTransaction;
import com.anbar.wms.inventory.service.StockSnapshotService.StockedRow;
import com.anbar.wms.inventory.service.StockMovementService;
import com.anbar.wms.inventory.service.StockMovementService.AdjustmentLine;
import com.anbar.wms.inventory.service.StockSnapshotService;
import com.anbar.wms.security.AuthenticatedUser;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.service.WarehouseService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inventory audit workflow. Every state change locks the audit row first, so concurrent requests on one audit run
 * one after another; completion in particular posts its adjustments exactly once.
 *
 * <p>Movements during an audit are allowed. Each item's expected quantity is the balance at the moment it is
 * counted (share-locked while the count is recorded), and completion posts counted − expected against the balance
 * as it is then, so stock that moved after the count is neither lost nor double-counted.
 */
@Service
public class InventoryAuditService {

    static final String RESOURCE = "Inventory audit";
    private static final Set<AuditStatus> OPEN_FULL = EnumSet.of(
            AuditStatus.IN_PROGRESS, AuditStatus.PENDING_APPROVAL, AuditStatus.APPROVED);
    private static final String ACTIVE_FULL_INDEX = "uq_inventory_audit_active_full";
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("auditNumber", "auditNumber", "status", "status", "createdAt", "createdAt",
                    "completedAt", "completedAt"),
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));

    private final InventoryAuditRepository audits;
    private final InventoryAuditQuery query;
    private final WarehouseService warehouses;
    private final ProductService products;
    private final StockSnapshotService snapshots;
    private final StockMovementService stockMovements;
    private final InventoryAuditMapper mapper;
    private final EntityManager entityManager;
    private final WmsProperties properties;
    private final Clock clock;

    public InventoryAuditService(InventoryAuditRepository audits, InventoryAuditQuery query,
                                 WarehouseService warehouses, ProductService products, StockSnapshotService snapshots,
                                 StockMovementService stockMovements, InventoryAuditMapper mapper,
                                 EntityManager entityManager, WmsProperties properties, Clock clock) {
        this.audits = audits;
        this.query = query;
        this.warehouses = warehouses;
        this.products = products;
        this.snapshots = snapshots;
        this.stockMovements = stockMovements;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<AuditResponse> search(WarehouseScope scope, String auditNumber, Long warehouseId,
                                              AuditStatus status, LocalDate from, LocalDate to, Pageable pageable) {
        return PageResponse.from(audits.findAll(InventoryAuditSpecifications.matching(scope, auditNumber, warehouseId,
                status, startOf(from), startOf(to == null ? null : to.plusDays(1))), SORT.apply(pageable)),
                mapper::toSummary);
    }

    @Transactional(readOnly = true)
    public AuditResponse get(long id, WarehouseScope scope) {
        InventoryAudit audit = audits.findWithItemsById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requireVisible(audit, scope);
        return detail(audit);
    }

    @Transactional
    public AuditResponse create(InventoryAuditRequests.Create request, AuthenticatedUser actor) {
        Warehouse warehouse = warehouses.requireActive(request.warehouseId());
        requireVisible(warehouse.getId(), actor.warehouseScope());
        AuditScope scope = Boolean.TRUE.equals(request.fullAudit()) ? AuditScope.FULL : AuditScope.PARTIAL;
        InventoryAudit audit = new InventoryAudit(nextNumber(), warehouse, scope, Boolean.TRUE.equals(request.blind()),
                blankToNull(request.notes()));
        if (scope == AuditScope.FULL) {
            addStocked(audit, null);
        }
        return detail(audits.saveAndFlush(audit));
    }

    @Transactional
    public AuditResponse addItems(long id, InventoryAuditRequests.AddItems request, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        for (InventoryAuditRequests.Item item : request.items()) {
            Product product = products.require(item.productId());
            if (product.isBatchTracked() && blankToNull(item.batchNumber()) == null) {
                if (addStocked(audit, product.getId()) == 0) {
                    throw new BusinessException(ErrorCode.BATCH_REQUIRED,
                            "%s has no stocked batch in this warehouse; name the batch to count".formatted(product.getSku()),
                            Map.of("productId", product.getId()));
                }
                continue;
            }
            StockBatch batch = blankToNull(item.batchNumber()) == null ? null : snapshots.batch(product, item.batchNumber());
            audit.addItem(product, batch, snapshots.stocked(audit.getWarehouse().getId(), product.getId()).stream()
                    .filter(row -> Objects.equals(row.batchId(), batch == null ? null : batch.getId()))
                    .map(StockedRow::quantity).findFirst().orElse(BigDecimal.ZERO));
        }
        return detail(audits.saveAndFlush(audit));
    }

    /** Full audits pick up stock that arrived since they were created. */
    @Transactional
    public AuditResponse start(long id, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        if (audit.getScope() == AuditScope.FULL && audits.existsByWarehouseIdAndScopeAndStatusIn(
                audit.getWarehouse().getId(), AuditScope.FULL, OPEN_FULL)) {
            throw anotherFullAuditOpen();
        }
        audit.start(user(actor), clock.instant());
        if (audit.getScope() == AuditScope.FULL) {
            addStocked(audit, null);
        }
        if (audit.getItems().isEmpty()) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION, "Add items before starting the audit");
        }
        try {
            return detail(audits.saveAndFlush(audit));
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.violates(ex, ACTIVE_FULL_INDEX)) {
                throw anotherFullAuditOpen();
            }
            throw ex;
        }
    }

    @Transactional
    public AuditResponse count(long id, long itemId, InventoryAuditRequests.Count request, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        InventoryAuditItem item = audit.getItems().stream().filter(i -> i.getId() == itemId).findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Inventory audit item", itemId));
        audit.requireCounting();
        if (!item.getProduct().getUnitOfMeasure().accepts(request.countedQuantity())) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY_SCALE,
                    "Quantities of %s allow at most %d decimal places".formatted(
                            item.getProduct().getUnitOfMeasure().getCode(),
                            item.getProduct().getUnitOfMeasure().getDecimalScale()),
                    Map.of("field", "countedQuantity"));
        }
        BigDecimal systemNow = snapshots.lockQuantity(audit.getWarehouse().getId(), item.getProduct().getId(),
                item.getBatch() == null ? null : item.getBatch().getId());
        item.recordCount(request.countedQuantity(), systemNow, blankToNull(request.notes()), user(actor), clock.instant());
        return detail(audits.saveAndFlush(audit));
    }

    @Transactional
    public AuditResponse submit(long id, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        audit.submit(user(actor), clock.instant());
        return detail(audits.saveAndFlush(audit));
    }

    @Transactional
    public AuditResponse approve(long id, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        audit.approve(user(actor), clock.instant());
        return detail(audits.saveAndFlush(audit));
    }

    @Transactional
    public AuditResponse reject(long id, String reason, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        audit.reject(reason.trim());
        return detail(audits.saveAndFlush(audit));
    }

    /**
     * Posts every non-zero discrepancy as one ADJUSTMENT document and closes the audit. A shortage larger than the
     * current balance (stock left after the count) fails the whole completion and nothing is posted.
     */
    @Transactional
    public AuditResponse complete(long id, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        audit.requireCompletable();
        List<AdjustmentLine> lines = audit.getItems().stream()
                .filter(i -> i.difference().signum() != 0)
                .map(i -> new AdjustmentLine(i.getProduct().getId(), i.getBatch() == null ? null : i.getBatch().getId(),
                        i.difference()))
                .toList();
        StockTransaction adjustments = lines.isEmpty() ? null : stockMovements.postAdjustments(
                audit.getWarehouse().getId(), audit.getAuditNumber(), "Inventory audit " + audit.getAuditNumber(),
                lines, actor);
        audit.complete(user(actor), clock.instant(), adjustments);
        return detail(audits.saveAndFlush(audit));
    }

    @Transactional
    public AuditResponse cancel(long id, AuthenticatedUser actor) {
        InventoryAudit audit = lock(id, actor.warehouseScope());
        audit.cancel();
        return detail(audits.saveAndFlush(audit));
    }

    static void requireVisible(long warehouseId, WarehouseScope scope) {
        if (!scope.allows(warehouseId)) {
            throw new ResourceNotFoundException("Warehouse", warehouseId);
        }
    }

    Instant startOf(LocalDate date) {
        return date == null ? null : date.atStartOfDay(properties.businessTimezone()).toInstant();
    }

    private int addStocked(InventoryAudit audit, Long productId) {
        int added = 0;
        for (StockedRow row : snapshots.stocked(audit.getWarehouse().getId(), productId)) {
            Product product = products.require(row.productId());
            StockBatch batch = row.batchId() == null ? null : snapshots.batch(row.batchId());
            if (audit.findItem(product, batch).isEmpty()) {
                audit.addItem(product, batch, row.quantity());
                added++;
            }
        }
        return added;
    }

    private InventoryAudit lock(long id, WarehouseScope scope) {
        InventoryAudit audit = audits.lockById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requireVisible(audit, scope);
        return audit;
    }

    private static void requireVisible(InventoryAudit audit, WarehouseScope scope) {
        if (!scope.allows(audit.getWarehouse().getId())) {
            throw new ResourceNotFoundException(RESOURCE, audit.getId());
        }
    }

    private AuditResponse detail(InventoryAudit audit) {
        return mapper.toDetail(audit, audit.getId() == null ? Map.of() : query.movementsSinceCount(audit.getId()));
    }

    private AppUser user(AuthenticatedUser actor) {
        return entityManager.getReference(AppUser.class, actor.id());
    }

    private String nextNumber() {
        return "IA-%d-%06d".formatted(Year.now(clock).getValue(), audits.nextAuditNumber());
    }

    private static BusinessException anotherFullAuditOpen() {
        return new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                "Another full audit of this warehouse is already open");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
