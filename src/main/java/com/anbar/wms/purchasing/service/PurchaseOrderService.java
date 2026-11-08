package com.anbar.wms.purchasing.service;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.service.ProductService;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.web.PageResponse;
import com.anbar.wms.common.web.SortWhitelist;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.purchasing.dto.PurchaseOrderRequests;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.PurchaseOrderResponse;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.SummaryRow;
import com.anbar.wms.purchasing.entity.PurchaseOrder;
import com.anbar.wms.purchasing.entity.PurchaseOrder.LineDraft;
import com.anbar.wms.purchasing.mapper.PurchaseOrderMapper;
import com.anbar.wms.purchasing.model.PurchaseOrderStatus;
import com.anbar.wms.purchasing.repository.PurchaseOrderRepository;
import com.anbar.wms.purchasing.repository.PurchaseOrderSpecifications;
import com.anbar.wms.purchasing.repository.PurchaseSummaryRow;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.supplier.service.SupplierService;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.warehouse.service.WarehouseService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Purchase order lifecycle. Orders never touch stock; only receipts do (see {@link PurchaseReceivingService}). */
@Service
public class PurchaseOrderService {

    static final String RESOURCE = "Purchase order";
    private static final String DEFAULT_CURRENCY = "AZN";
    private static final SortWhitelist SORT = SortWhitelist.of(
            Map.of("orderNumber", "poNumber", "orderDate", "orderDate", "expectedDeliveryDate", "expectedDeliveryDate",
                    "status", "status", "createdAt", "createdAt"),
            Sort.by(Sort.Order.desc("orderDate"), Sort.Order.desc("id")));

    public enum SummaryGrouping { SUPPLIER, WAREHOUSE, STATUS }

    private final PurchaseOrderRepository orders;
    private final SupplierService suppliers;
    private final WarehouseService warehouses;
    private final ProductService products;
    private final PurchaseOrderMapper mapper;
    private final EntityManager entityManager;
    private final Clock clock;

    public PurchaseOrderService(PurchaseOrderRepository orders, SupplierService suppliers, WarehouseService warehouses,
                                ProductService products, PurchaseOrderMapper mapper, EntityManager entityManager,
                                Clock clock) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.warehouses = warehouses;
        this.products = products;
        this.mapper = mapper;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public PageResponse<PurchaseOrderResponse> search(WarehouseScope scope, String orderNumber, Long supplierId,
                                                      Long warehouseId, PurchaseOrderStatus status, boolean outstanding,
                                                      LocalDate from, LocalDate to, Pageable pageable) {
        return PageResponse.from(orders.findAll(PurchaseOrderSpecifications.matching(scope, orderNumber, supplierId,
                warehouseId, status, outstanding, from, to), SORT.apply(pageable)), mapper::toResponse);
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(long id, WarehouseScope scope) {
        PurchaseOrder order = orders.findWithLinesById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        requireVisible(order, scope);
        return mapper.toResponse(order);
    }

    @Transactional
    public PurchaseOrderResponse create(PurchaseOrderRequests.Create request) {
        LocalDate orderDate = request.orderDate() != null ? request.orderDate() : LocalDate.now(clock);
        PurchaseOrder order = new PurchaseOrder(nextNumber(orderDate),
                suppliers.requireActive(request.supplierId()), warehouses.requireActive(request.warehouseId()),
                currency(request.currency()), orderDate);
        order.updateHeader(order.getSupplier(), order.getWarehouse(), order.getCurrency(), orderDate,
                deliveryDate(orderDate, request.expectedDeliveryDate()), blankToNull(request.notes()));
        order.replaceLines(lineDrafts(request.items()));
        return mapper.toResponse(orders.saveAndFlush(order));
    }

    @Transactional
    public PurchaseOrderResponse update(long id, PurchaseOrderRequests.Update request) {
        PurchaseOrder order = lock(id);
        order.verifyVersion(request.version());
        // Line edits alone don't make the order row dirty; bump the version so stale edits of items are rejected too.
        entityManager.lock(order, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        Supplier supplier = order.getSupplier().getId().equals(request.supplierId())
                ? order.getSupplier() : suppliers.requireActive(request.supplierId());
        Warehouse warehouse = order.getWarehouse().getId().equals(request.warehouseId())
                ? order.getWarehouse() : warehouses.requireActive(request.warehouseId());
        order.updateHeader(supplier, warehouse, currency(request.currency()), request.orderDate(),
                deliveryDate(request.orderDate(), request.expectedDeliveryDate()), blankToNull(request.notes()));
        List<LineDraft> drafts = lineDrafts(request.items());
        // Hibernate inserts before it deletes orphans; the old lines must be gone first or line numbers collide.
        order.replaceLines(List.of());
        orders.flush();
        order.replaceLines(drafts);
        return mapper.toResponse(orders.saveAndFlush(order));
    }

    @Transactional
    public PurchaseOrderResponse submit(long id) {
        PurchaseOrder order = lock(id);
        order.submit();
        return mapper.toResponse(orders.saveAndFlush(order));
    }

    @Transactional
    public PurchaseOrderResponse approve(long id, long approverId) {
        PurchaseOrder order = lock(id);
        order.approve(entityManager.getReference(AppUser.class, approverId), clock.instant());
        return mapper.toResponse(orders.saveAndFlush(order));
    }

    @Transactional
    public PurchaseOrderResponse reject(long id, String reason) {
        PurchaseOrder order = lock(id);
        order.reject(reason.trim());
        return mapper.toResponse(orders.saveAndFlush(order));
    }

    @Transactional
    public PurchaseOrderResponse cancel(long id, String reason) {
        PurchaseOrder order = lock(id);
        order.cancel(reason.trim());
        return mapper.toResponse(orders.saveAndFlush(order));
    }

    /** Orders and ordered value per supplier, warehouse or status; sortable by label, orderCount, totalAmount. */
    @Transactional(readOnly = true)
    public PageResponse<SummaryRow> summary(SummaryGrouping grouping, WarehouseScope scope, LocalDate from,
                                            LocalDate to, Pageable pageable) {
        LocalDate start = from != null ? from : LocalDate.of(1900, 1, 1);
        LocalDate end = to != null ? to : LocalDate.of(9999, 12, 31);
        Set<Long> ids = scope.unrestricted() || scope.warehouseIds().isEmpty() ? Set.of(-1L) : scope.warehouseIds();
        List<PurchaseSummaryRow> rows = switch (grouping) {
            case SUPPLIER -> orders.summarizeBySupplier(scope.unrestricted(), ids, start, end);
            case WAREHOUSE -> orders.summarizeByWarehouse(scope.unrestricted(), ids, start, end);
            case STATUS -> orders.summarizeByStatus(scope.unrestricted(), ids, start, end);
        };
        List<SummaryRow> sorted = new ArrayList<>(rows.stream()
                .map(r -> new SummaryRow(r.key(), r.label(), r.orderCount(), r.totalAmount())).toList());
        sorted.sort(summaryOrder(pageable.getSort()));
        int fromIndex = (int) Math.min(pageable.getOffset(), sorted.size());
        int toIndex = Math.min(fromIndex + pageable.getPageSize(), sorted.size());
        return PageResponse.from(new PageImpl<>(sorted.subList(fromIndex, toIndex), pageable, sorted.size()));
    }

    PurchaseOrder lock(long id) {
        return orders.lockById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    static void requireVisible(PurchaseOrder order, WarehouseScope scope) {
        if (!scope.allows(order.getWarehouse().getId())) {
            throw new ResourceNotFoundException(RESOURCE, order.getId());
        }
    }

    private List<LineDraft> lineDrafts(List<PurchaseOrderRequests.Item> items) {
        Set<Long> seen = new HashSet<>();
        List<LineDraft> drafts = new ArrayList<>();
        for (PurchaseOrderRequests.Item item : items) {
            if (!seen.add(item.productId())) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                        "Each product may appear only once per order; combine the quantities",
                        Map.of("field", "items", "productId", item.productId()));
            }
            Product product = products.requireActive(item.productId());
            if (!product.getUnitOfMeasure().accepts(item.orderedQuantity())) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY_SCALE,
                        "orderedQuantity of %s allows at most %d decimal places".formatted(
                                product.getSku(), product.getUnitOfMeasure().getDecimalScale()),
                        Map.of("field", "orderedQuantity", "productId", item.productId()));
            }
            drafts.add(new LineDraft(product, item.orderedQuantity(), item.unitPrice(), blankToNull(item.notes())));
        }
        return drafts;
    }

    private String nextNumber(LocalDate orderDate) {
        return "PO-%d-%06d".formatted(orderDate.getYear(), orders.nextOrderNumber());
    }

    private static Currency currency(String code) {
        try {
            return Currency.getInstance(code == null ? DEFAULT_CURRENCY : code.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED, "Unknown currency " + code, Map.of("field", "currency"));
        }
    }

    private static LocalDate deliveryDate(LocalDate orderDate, LocalDate expected) {
        if (expected != null && expected.isBefore(orderDate)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED,
                    "The expected delivery date can't be before the order date", Map.of("field", "expectedDeliveryDate"));
        }
        return expected;
    }

    private static Comparator<SummaryRow> summaryOrder(Sort sort) {
        Comparator<SummaryRow> order = null;
        for (Sort.Order o : sort) {
            Comparator<SummaryRow> next = switch (o.getProperty()) {
                case "label" -> Comparator.comparing(SummaryRow::label, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                case "orderCount" -> Comparator.comparingLong(SummaryRow::orderCount);
                case "totalAmount" -> Comparator.comparing(SummaryRow::totalAmount);
                default -> throw new BusinessException(ErrorCode.INVALID_SORT_PROPERTY,
                        "Sorting by '%s' is not supported".formatted(o.getProperty()),
                        Map.of("property", o.getProperty(), "allowed", List.of("label", "orderCount", "totalAmount")));
            };
            next = o.isAscending() ? next : next.reversed();
            order = order == null ? next : order.thenComparing(next);
        }
        return order != null ? order : Comparator.comparing(SummaryRow::totalAmount).reversed();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
