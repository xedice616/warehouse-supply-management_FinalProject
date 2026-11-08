package com.anbar.wms.purchasing.mapper;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.ItemResponse;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.ProductRef;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.PurchaseOrderResponse;
import com.anbar.wms.purchasing.dto.PurchaseOrderResponses.Ref;
import com.anbar.wms.purchasing.entity.PurchaseOrder;
import com.anbar.wms.purchasing.entity.PurchaseOrderLine;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.warehouse.entity.Warehouse;
import java.util.Currency;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface PurchaseOrderMapper {

    @Mapping(target = "orderNumber", source = "poNumber")
    @Mapping(target = "destinationWarehouse", source = "warehouse")
    @Mapping(target = "totalAmount", expression = "java(order.totalAmount())")
    @Mapping(target = "items", source = "lines")
    PurchaseOrderResponse toResponse(PurchaseOrder order);

    @Mapping(target = "outstandingQuantity", expression = "java(line.outstandingQuantity())")
    @Mapping(target = "totalPrice", expression = "java(line.lineAmount())")
    ItemResponse toResponse(PurchaseOrderLine line);

    @Mapping(target = "unitCode", source = "unitOfMeasure.code")
    ProductRef toRef(Product product);

    Ref toRef(Supplier supplier);

    Ref toRef(Warehouse warehouse);

    default String username(AppUser user) {
        return user == null ? null : user.getUsername();
    }

    default String currencyCode(Currency currency) {
        return currency == null ? null : currency.getCurrencyCode();
    }
}
