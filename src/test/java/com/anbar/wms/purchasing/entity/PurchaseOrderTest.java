package com.anbar.wms.purchasing.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.ProductDetails;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.purchasing.entity.PurchaseOrder.LineDraft;
import com.anbar.wms.purchasing.model.PurchaseOrderStatus;
import com.anbar.wms.supplier.entity.Supplier;
import com.anbar.wms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchaseOrderTest {

    private static final AppUser APPROVER = new AppUser("boss", "boss@example.test", "x", "Boss");

    private PurchaseOrder order;

    @BeforeEach
    void setUp() {
        order = new PurchaseOrder("PO-2026-000001", new Supplier("S1", "Supplier", null),
                new Warehouse("W1", "Main", null, null), Currency.getInstance("AZN"), LocalDate.of(2026, 11, 8));
        order.replaceLines(List.of(
                new LineDraft(product("A"), new BigDecimal("10"), new BigDecimal("2.50"), null),
                new LineDraft(product("B"), new BigDecimal("4"), new BigDecimal("10"), null)));
    }

    @Test
    void totalIsTheSumOfLineAmounts() {
        assertThat(order.totalAmount()).isEqualByComparingTo("65");
    }

    @Test
    void walksTheApprovalWorkflow() {
        order.submit();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SUBMITTED);
        order.approve(APPROVER, Instant.EPOCH);
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.APPROVED);
    }

    @Test
    void rejectionReturnsToDraftAndAllowsEditing() {
        order.submit();
        order.reject("Wrong prices");

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(order.getRejectionReason()).isEqualTo("Wrong prices");
        order.replaceLines(List.of(new LineDraft(product("A"), BigDecimal.ONE, BigDecimal.ONE, null)));
        assertThat(order.getLines()).hasSize(1);
    }

    @Test
    void submittedOrdersCannotBeEdited() {
        order.submit();

        assertThatThrownBy(() -> order.replaceLines(List.of()))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void emptyOrdersCannotBeSubmitted() {
        order.replaceLines(List.of());

        assertThatThrownBy(order::submit).isInstanceOf(BusinessException.class);
    }

    @Test
    void partialThenFullReceiptUpdatesTheStatus() {
        approve();
        order.getLines().get(0).receive(new BigDecimal("4"));
        order.refreshReceivingStatus();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(order.getLines().get(0).outstandingQuantity()).isEqualByComparingTo("6");

        order.getLines().get(0).receive(new BigDecimal("6"));
        order.getLines().get(1).receive(new BigDecimal("4"));
        order.refreshReceivingStatus();
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
    }

    @Test
    void receivingMoreThanOrderedIsRefused() {
        approve();

        assertThatThrownBy(() -> order.getLines().get(1).receive(new BigDecimal("5")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.RECEIPT_EXCEEDS_ORDER));
    }

    @Test
    void cancelledOrdersCannotReceiveOrBeCancelledAgain() {
        approve();
        order.cancel("Supplier closed");

        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThatThrownBy(order::refreshReceivingStatus).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> order.cancel("again")).isInstanceOf(BusinessException.class);
    }

    @Test
    void fullyReceivedOrdersCannotBeCancelled() {
        approve();
        order.getLines().forEach(line -> line.receive(line.getOrderedQuantity()));
        order.refreshReceivingStatus();

        assertThatThrownBy(() -> order.cancel("late")).isInstanceOf(BusinessException.class);
    }

    private void approve() {
        order.submit();
        order.approve(APPROVER, Instant.EPOCH);
    }

    private static Product product(String sku) {
        return new Product(sku, new ProductDetails(sku, null, null, new ProductCategory("C", null),
                new UnitOfMeasure("PCS", "Piece", 0), false, false, BigDecimal.ZERO, BigDecimal.ONE, null));
    }
}
