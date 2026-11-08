package com.anbar.wms.inventory.mapper;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.dto.StockMovementResponse;
import com.anbar.wms.inventory.dto.StockMovementResponse.BatchRef;
import com.anbar.wms.inventory.dto.StockMovementResponse.ProductRef;
import com.anbar.wms.inventory.dto.StockMovementResponse.SupplierRef;
import com.anbar.wms.inventory.dto.StockMovementResponse.UserRef;
import com.anbar.wms.inventory.dto.StockMovementResponse.WarehouseRef;
import com.anbar.wms.inventory.entity.StockBatch;
import com.anbar.wms.inventory.entity.StockMovement;
import com.anbar.wms.inventory.entity.StockTransaction;
import java.math.BigDecimal;
import java.util.List;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.warehouse.entity.Warehouse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface StockMovementMapper {

    @Mapping(target = "movementNumber", source = "transaction.transactionNumber")
    @Mapping(target = "supplier", source = "transaction.supplier")
    @Mapping(target = "referenceNumber", source = "transaction.referenceNumber")
    @Mapping(target = "reason", source = "transaction.reason")
    @Mapping(target = "movementDate", source = "performedAt")
    @Mapping(target = "createdAt", source = "performedAt")
    @Mapping(target = "allocations", ignore = true)
    StockMovementResponse toResponse(StockMovement movement);

    @Mapping(target = "movementId", source = "id")
    StockMovementResponse.Allocation toAllocation(StockMovement movement);

    /** The response to a posting: its first line plus every line as an allocation. */
    default StockMovementResponse toPostingResponse(StockTransaction transaction) {
        List<StockMovement> lines = transaction.getMovements();
        BigDecimal total = lines.stream().map(StockMovement::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        return toResponse(lines.getFirst()).withAllocations(total, lines.stream().map(this::toAllocation).toList());
    }

    @Mapping(target = "unitCode", source = "unitOfMeasure.code")
    ProductRef toRef(Product product);

    BatchRef toRef(StockBatch batch);

    WarehouseRef toRef(Warehouse warehouse);

    @Mapping(target = "companyName", source = "name")
    SupplierRef toRef(Supplier supplier);

    UserRef toRef(AppUser user);
}
