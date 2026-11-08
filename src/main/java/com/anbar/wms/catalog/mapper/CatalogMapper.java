package com.anbar.wms.catalog.mapper;

import com.anbar.wms.catalog.dto.CatalogResponses.CategoryRef;
import com.anbar.wms.catalog.dto.CatalogResponses.CategoryResponse;
import com.anbar.wms.catalog.dto.CatalogResponses.ProductResponse;
import com.anbar.wms.catalog.dto.CatalogResponses.UnitRef;
import com.anbar.wms.catalog.dto.CatalogResponses.UnitResponse;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface CatalogMapper {

    UnitResponse toResponse(UnitOfMeasure unit);

    @Mapping(target = "parentId", source = "parent.id")
    CategoryResponse toResponse(ProductCategory category);

    @Mapping(target = "unit", source = "unitOfMeasure")
    ProductResponse toResponse(Product product);

    CategoryRef toRef(ProductCategory category);

    UnitRef toRef(UnitOfMeasure unit);
}
