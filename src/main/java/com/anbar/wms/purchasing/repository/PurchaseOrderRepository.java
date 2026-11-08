package com.anbar.wms.purchasing.repository;

import com.anbar.wms.purchasing.entity.PurchaseOrder;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Lines belong to the order aggregate and are saved through it; there is no separate line repository. */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long>,
        JpaSpecificationExecutor<PurchaseOrder> {

    Optional<PurchaseOrder> findByPoNumber(String poNumber);

    @EntityGraph(attributePaths = {"lines", "lines.product"})
    Optional<PurchaseOrder> findWithLinesById(Long id);

    /** {@code SELECT ... FOR UPDATE}: receipts and workflow changes on one order run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select po from PurchaseOrder po where po.id = :id")
    Optional<PurchaseOrder> lockById(@Param("id") Long id);

    @Query(value = "SELECT nextval('purchase_order_number_seq')", nativeQuery = true)
    long nextOrderNumber();

    String SUMMARY_FILTER = """
            where (:allWarehouses = true or po.warehouse.id in :warehouseIds)
              and po.orderDate >= :from and po.orderDate <= :to""";

    @Query("""
            select new com.anbar.wms.purchasing.repository.PurchaseSummaryRow(
                cast(s.id as string), s.name, count(distinct po.id), coalesce(sum(l.orderedQuantity * l.unitPrice), 0))
            from PurchaseOrder po join po.supplier s left join po.lines l
            """ + SUMMARY_FILTER + " group by s.id, s.name")
    List<PurchaseSummaryRow> summarizeBySupplier(@Param("allWarehouses") boolean allWarehouses,
                                         @Param("warehouseIds") Collection<Long> warehouseIds,
                                         @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select new com.anbar.wms.purchasing.repository.PurchaseSummaryRow(
                cast(w.id as string), w.name, count(distinct po.id), coalesce(sum(l.orderedQuantity * l.unitPrice), 0))
            from PurchaseOrder po join po.warehouse w left join po.lines l
            """ + SUMMARY_FILTER + " group by w.id, w.name")
    List<PurchaseSummaryRow> summarizeByWarehouse(@Param("allWarehouses") boolean allWarehouses,
                                          @Param("warehouseIds") Collection<Long> warehouseIds,
                                          @Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            select new com.anbar.wms.purchasing.repository.PurchaseSummaryRow(
                cast(po.status as string), cast(po.status as string), count(distinct po.id),
                coalesce(sum(l.orderedQuantity * l.unitPrice), 0))
            from PurchaseOrder po left join po.lines l
            """ + SUMMARY_FILTER + " group by po.status")
    List<PurchaseSummaryRow> summarizeByStatus(@Param("allWarehouses") boolean allWarehouses,
                                       @Param("warehouseIds") Collection<Long> warehouseIds,
                                       @Param("from") LocalDate from, @Param("to") LocalDate to);
}
