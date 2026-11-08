package com.anbar.wms.supplier.repository;

import com.anbar.wms.common.repository.SearchPatterns;
import com.anbar.wms.supplier.entity.Supplier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface SupplierRepository extends JpaRepository<Supplier, Long>, JpaSpecificationExecutor<Supplier> {

    Optional<Supplier> findByCode(String code);

    boolean existsByCode(String code);

    boolean existsByTaxId(String taxId);

    boolean existsByTaxIdAndIdNot(String taxId, Long id);

    @Query(value = "SELECT nextval('supplier_code_seq')", nativeQuery = true)
    long nextCodeNumber();

    /** q matches a company-name prefix, a tax-id prefix or a code prefix, ignoring case. */
    static Specification<Supplier> search(String search, Boolean active) {
        List<Specification<Supplier>> filters = new ArrayList<>();
        if (!SearchPatterns.isBlank(search)) {
            String pattern = SearchPatterns.lowerPrefix(search);
            String upper = search.trim().toUpperCase(Locale.ROOT);
            filters.add((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("name")), pattern, SearchPatterns.ESCAPE),
                    cb.like(root.get("taxId"), upper + "%"),
                    cb.like(root.get("code"), upper + "%")));
        }
        if (active != null) {
            filters.add((root, query, cb) -> cb.equal(root.get("active"), active));
        }
        return Specification.allOf(filters);
    }
}
