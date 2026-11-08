package com.anbar.wms.catalog.repository;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.repository.view.CatalogViews.ProductSummary;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** SKUs are stored upper-case; pass normalized values. */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

    Optional<Product> findBySku(String sku);

    Optional<Product> findByBarcode(String barcode);

    /**
     * Lightweight product search. Every filter is optional (null = ignore) and they combine with AND. Returns a
     * projection, so no lazy relationships are loaded; {@code sku} and {@code barcode} are exact, {@code namePattern}
     * is a lower-cased {@code LIKE} prefix. All three hit covering indexes.
     */
    @Query("""
            select new com.anbar.wms.catalog.repository.view.CatalogViews$ProductSummary(
                p.id, p.sku, p.name, p.barcode, c.id, c.name, u.code, p.active)
            from Product p join p.category c join p.unitOfMeasure u
            where (:sku is null or p.sku = :sku)
              and (:barcode is null or p.barcode = :barcode)
              and (:namePattern is null or lower(p.name) like :namePattern escape '\\')
              and (:categoryId is null or c.id = :categoryId)
              and (:active is null or p.active = :active)""")
    Page<ProductSummary> search(@Param("sku") String sku, @Param("barcode") String barcode,
                                @Param("namePattern") String namePattern, @Param("categoryId") Long categoryId,
                                @Param("active") Boolean active, Pageable pageable);

    boolean existsBySku(String sku);

    boolean existsByCategoryId(Long categoryId);

    boolean existsByUnitOfMeasureId(Long unitId);
}
