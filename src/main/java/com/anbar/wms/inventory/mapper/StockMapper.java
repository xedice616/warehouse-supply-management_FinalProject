package com.anbar.wms.inventory.mapper;

import com.anbar.wms.inventory.dto.StockResponses.BatchQuantityResponse;
import com.anbar.wms.inventory.dto.StockResponses.LowStockResponse;
import com.anbar.wms.inventory.dto.StockResponses.StockLineResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseProductResponse;
import com.anbar.wms.inventory.dto.StockResponses.WarehouseQuantityResponse;
import com.anbar.wms.inventory.repository.view.StockViews.BatchQuantity;
import com.anbar.wms.inventory.repository.view.StockViews.LowStock;
import com.anbar.wms.inventory.repository.view.StockViews.ProductQuantity;
import com.anbar.wms.inventory.repository.view.StockViews.StockLine;
import com.anbar.wms.inventory.repository.view.StockViews.WarehouseQuantity;
import java.math.BigDecimal;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface StockMapper {

    StockLineResponse toResponse(StockLine line);

    @Mapping(target = "belowMinimum", expression = "java(isBelow(view.quantity(), view.minStockQuantity()))")
    WarehouseProductResponse toResponse(ProductQuantity view);

    WarehouseQuantityResponse toResponse(WarehouseQuantity view);

    BatchQuantityResponse toResponse(BatchQuantity view);

    LowStockResponse toResponse(LowStock view);

    default boolean isBelow(BigDecimal quantity, BigDecimal minimum) {
        return quantity.compareTo(minimum) < 0;
    }
}
