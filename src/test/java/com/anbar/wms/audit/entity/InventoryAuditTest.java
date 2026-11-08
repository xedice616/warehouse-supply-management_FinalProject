package com.anbar.wms.audit.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anbar.wms.audit.model.AuditScope;
import com.anbar.wms.audit.model.AuditStatus;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.ProductDetails;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.warehouse.entity.Warehouse;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InventoryAuditTest {

    private static final AppUser USER = new AppUser("counter", "counter@example.test", "x", "Counter");
    private static final Instant NOW = Instant.parse("2026-11-08T10:00:00Z");

    private InventoryAudit audit;
    private InventoryAuditItem item;

    @BeforeEach
    void setUp() {
        audit = new InventoryAudit("IA-2026-000001", new Warehouse("W1", "Main", null, null), AuditScope.PARTIAL, true, null);
        item = audit.addItem(product(), null, new BigDecimal("10"));
    }

    @Test
    void countSnapshotsTheSystemQuantityAtCountTime() {
        audit.start(USER, NOW);
        item.recordCount(new BigDecimal("8"), new BigDecimal("12"), null, USER, NOW);

        assertThat(item.getExpectedQuantity()).isEqualByComparingTo("12");
        assertThat(item.difference()).isEqualByComparingTo("-4");
    }

    @Test
    void countingNeedsAStartedAudit() {
        assertThatThrownBy(() -> item.recordCount(BigDecimal.ONE, BigDecimal.ONE, null, USER, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void submissionNeedsEveryItemCounted() {
        audit.start(USER, NOW);

        assertThatThrownBy(() -> audit.submit(USER, NOW))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getProperties()).containsEntry("uncounted", 1L));
    }

    @Test
    void rejectionReturnsToCountingAndKeepsCounts() {
        countAndSubmit();
        audit.reject("Recount shelf 3");

        assertThat(audit.getStatus()).isEqualTo(AuditStatus.IN_PROGRESS);
        assertThat(audit.getSubmittedAt()).isNull();
        assertThat(item.isCounted()).isTrue();
    }

    @Test
    void completesOnceAfterApproval() {
        countAndSubmit();
        assertThatThrownBy(audit::requireCompletable).isInstanceOf(BusinessException.class);

        audit.approve(USER, NOW);
        audit.requireCompletable();
        audit.complete(USER, NOW, null);

        assertThat(audit.getStatus()).isEqualTo(AuditStatus.COMPLETED);
        assertThatThrownBy(audit::requireCompletable).isInstanceOf(BusinessException.class);
        assertThatThrownBy(audit::cancel).isInstanceOf(BusinessException.class);
    }

    @Test
    void duplicateItemsAreRefused() {
        assertThatThrownBy(() -> audit.addItem(item.getProduct(), null, BigDecimal.ONE))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.DUPLICATE_RESOURCE));
    }

    @Test
    void blindAuditsRevealSystemQuantitiesOnlyAfterCounting() {
        assertThat(audit.isCountingOver()).isFalse();
        countAndSubmit();
        assertThat(audit.isCountingOver()).isTrue();
    }

    private void countAndSubmit() {
        audit.start(USER, NOW);
        item.recordCount(new BigDecimal("9"), new BigDecimal("10"), null, USER, NOW);
        audit.submit(USER, NOW);
    }

    private static Product product() {
        return new Product("BOLT", new ProductDetails("Bolt", null, null, new ProductCategory("C", null),
                new UnitOfMeasure("PCS", "Piece", 0), false, false, BigDecimal.ZERO, BigDecimal.ONE, null));
    }
}
