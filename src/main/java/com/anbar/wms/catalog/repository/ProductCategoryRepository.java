package com.anbar.wms.catalog.repository;

import com.anbar.wms.catalog.entity.ProductCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long>,
        JpaSpecificationExecutor<ProductCategory> {

    List<ProductCategory> findByParentIsNullOrderByNameAsc();

    List<ProductCategory> findByParentIdOrderByNameAsc(Long parentId);

    boolean existsByParentId(Long parentId);
}
