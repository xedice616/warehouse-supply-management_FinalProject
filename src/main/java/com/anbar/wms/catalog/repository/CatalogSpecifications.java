package com.anbar.wms.catalog.repository;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.common.repository.SearchPatterns;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

public final class CatalogSpecifications {

    private CatalogSpecifications() {
    }

    public static Specification<Product> products(String search, Long categoryId, Boolean active) {
        List<Specification<Product>> filters = new ArrayList<>();
        if (!SearchPatterns.isBlank(search)) {
            String pattern = SearchPatterns.lowerPrefix(search);
            filters.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("sku")), pattern, SearchPatterns.ESCAPE),
                    cb.like(cb.lower(root.get("name")), pattern, SearchPatterns.ESCAPE)));
        }
        if (categoryId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId));
        }
        if (active != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return Specification.allOf(filters);
    }

    public static Specification<ProductCategory> categories(String search, Long parentId, Boolean active) {
        List<Specification<ProductCategory>> filters = new ArrayList<>();
        if (!SearchPatterns.isBlank(search)) {
            String pattern = SearchPatterns.lowerPrefix(search);
            filters.add((root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, SearchPatterns.ESCAPE));
        }
        if (parentId != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("parent").get("id"), parentId));
        }
        if (active != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return Specification.allOf(filters);
    }

    public static Specification<UnitOfMeasure> units(Boolean active) {
        return active == null
                ? Specification.allOf()
                : (root, query, cb) -> cb.equal(root.get("active"), active);
    }
}
