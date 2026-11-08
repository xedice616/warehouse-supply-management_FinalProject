package com.anbar.wms.audit.mapper;

import com.anbar.wms.audit.dto.InventoryAuditResponses.AuditResponse;
import com.anbar.wms.audit.dto.InventoryAuditResponses.BatchRef;
import com.anbar.wms.audit.dto.InventoryAuditResponses.DiscrepancyRow;
import com.anbar.wms.audit.dto.InventoryAuditResponses.ItemResponse;
import com.anbar.wms.audit.dto.InventoryAuditResponses.ProductRef;
import com.anbar.wms.audit.dto.InventoryAuditResponses.Step;
import com.anbar.wms.audit.dto.InventoryAuditResponses.WarehouseRef;
import com.anbar.wms.audit.dto.InventoryAuditResponses.WarehouseStatistics;
import com.anbar.wms.audit.entity.InventoryAudit;
import com.anbar.wms.audit.entity.InventoryAuditItem;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.audit.repository.InventoryAuditQuery;
import com.anbar.wms.audit.repository.InventoryAuditQuery.MovementsSince;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockBatch;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class InventoryAuditMapper {

    public AuditResponse toSummary(InventoryAudit audit) {
        return toResponse(audit, null);
    }

    public AuditResponse toDetail(InventoryAudit audit, Map<Long, MovementsSince> sinceCount) {
        boolean hideSystem = audit.isBlind() && !audit.isCountingOver();
        List<ItemResponse> items = audit.getItems().stream()
                .map(item -> toItem(item, hideSystem, sinceCount.get(item.getId())))
                .toList();
        return toResponse(audit, items);
    }

    public DiscrepancyRow toDiscrepancy(InventoryAuditQuery.DiscrepancyRow row) {
        return new DiscrepancyRow(row.auditId(), row.auditNumber(), AuditStatus.valueOf(row.auditStatus()),
                new WarehouseRef(row.warehouseId(), row.warehouseCode(), row.warehouseName()),
                new ProductRef(row.productId(), row.sku(), row.productName(), row.unitCode()),
                row.batchId() == null ? null : new BatchRef(row.batchId(), row.batchNumber(), row.expirationDate()),
                row.expectedQuantity(), row.countedQuantity(), row.discrepancy(), row.notes(), row.countedAt());
    }

    public WarehouseStatistics toStatistics(InventoryAuditQuery.WarehouseRow row) {
        return new WarehouseStatistics(new WarehouseRef(row.warehouseId(), row.warehouseCode(), row.warehouseName()),
                row.audits(), row.completed(), row.open(), row.cancelled(), row.countedItems(), row.discrepancyItems(),
                row.surplus(), row.shortage(), row.lastCompletedAt());
    }

    private AuditResponse toResponse(InventoryAudit audit, List<ItemResponse> items) {
        List<InventoryAuditItem> all = audit.getItems();
        int counted = (int) all.stream().filter(InventoryAuditItem::isCounted).count();
        int discrepant = (int) all.stream().filter(i -> i.isCounted() && i.difference().signum() != 0).count();
        return new AuditResponse(audit.getId(), audit.getAuditNumber(),
                new WarehouseRef(audit.getWarehouse().getId(), audit.getWarehouse().getCode(), audit.getWarehouse().getName()),
                audit.getScope(), audit.getStatus(), audit.isBlind(), audit.getNotes(), all.size(), counted,
                audit.isBlind() && !audit.isCountingOver() ? 0 : discrepant,
                step(audit.getStartedBy(), audit.getStartedAt()), step(audit.getSubmittedBy(), audit.getSubmittedAt()),
                step(audit.getApprovedBy(), audit.getApprovedAt()), step(audit.getCompletedBy(), audit.getCompletedAt()),
                audit.getRejectionReason(),
                audit.getAdjustmentTransaction() == null ? null : audit.getAdjustmentTransaction().getTransactionNumber(),
                audit.getVersion(), audit.getCreatedAt(), audit.getUpdatedAt(), items);
    }

    private static ItemResponse toItem(InventoryAuditItem item, boolean hideSystem, MovementsSince since) {
        Product product = item.getProduct();
        StockBatch batch = item.getBatch();
        return new ItemResponse(item.getId(),
                new ProductRef(product.getId(), product.getSku(), product.getName(), product.getUnitOfMeasure().getCode()),
                batch == null ? null : new BatchRef(batch.getId(), batch.getBatchNumber(), batch.getExpiresOn()),
                hideSystem ? null : item.getExpectedQuantity(), item.getCountedQuantity(),
                hideSystem ? null : item.difference(), item.getNotes(),
                item.getCountedBy() == null ? null : item.getCountedBy().getUsername(), item.getCountedAt(),
                hideSystem || since == null ? null : since.movements(),
                hideSystem || since == null ? null : since.netChange());
    }

    private static Step step(AppUser user, Instant at) {
        return at == null ? null : new Step(user == null ? null : user.getUsername(), at);
    }
}
