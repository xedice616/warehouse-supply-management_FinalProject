package com.anbar.wms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.ProductDetails;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.common.config.WmsProperties;
import com.anbar.wms.common.exception.InsufficientStockException;
import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.inventory.entity.StockMovement;
import com.anbar.wms.inventory.model.MovementType;
import com.anbar.wms.inventory.model.StockTransactionType;
import com.anbar.wms.inventory.repository.BalanceWriter;
import com.anbar.wms.inventory.repository.BalanceWriter.BatchStock;
import com.anbar.wms.inventory.repository.StockTransactionRepository;
import com.anbar.wms.warehouse.entity.Warehouse;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class InventoryPostingServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 8);

    @Mock
    private BalanceWriter balances;

    @Mock
    private StockTransactionRepository transactions;

    @Mock
    private EntityManager entityManager;

    private InventoryPostingService service;
    private Product product;
    private final Warehouse low = warehouse(1L);
    private final Warehouse high = warehouse(2L);

    @BeforeEach
    void setUp() {
        service = new InventoryPostingService(balances, transactions, entityManager,
                new WmsProperties(ZoneId.of("Asia/Baku")), Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC));
        product = new Product("BOLT", new ProductDetails("Bolt", null, null, new ProductCategory("C", null),
                new UnitOfMeasure("PCS", "Piece", 0), false, false, BigDecimal.ZERO, BigDecimal.ONE, null));
        ReflectionTestUtils.setField(product, "id", 9L);
    }

    @Test
    void transferRecordsBothBalancesAndLocksLowerWarehouseFirst() {
        when(balances.increase(1L, 9L, null, BigDecimal.TEN)).thenReturn(new BigDecimal("15"));
        when(balances.decrease(2L, 9L, null, BigDecimal.TEN)).thenReturn(Optional.of(new BigDecimal("30")));
        when(transactions.nextMovementNumber()).thenReturn(42L);
        when(entityManager.getReference(AppUser.class, 5L)).thenReturn(new AppUser("op", "op@example.test", "x", "Operator"));

        StockMovement movement = service.post(transfer(high, low), 5L).getMovements().getFirst();

        InOrder order = inOrder(balances);
        order.verify(balances).increase(1L, 9L, null, BigDecimal.TEN);
        order.verify(balances).decrease(2L, 9L, null, BigDecimal.TEN);
        assertThat(movement.getSourceBalanceAfter()).isEqualByComparingTo("30");
        assertThat(movement.getDestinationBalanceAfter()).isEqualByComparingTo("15");
        assertThat(movement.getTransaction().getTransactionNumber()).isEqualTo("SM-2026-000042");
        verify(transactions).save(any());
    }

    @Test
    void insufficientStockStopsBeforeAnythingIsRecorded() {
        when(balances.decrease(1L, 9L, null, BigDecimal.TEN)).thenReturn(Optional.empty());
        when(balances.available(1L, 9L, null)).thenReturn(new BigDecimal("3"));

        assertThatThrownBy(() -> service.post(transfer(low, high), 5L))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        ex -> assertThat(ex.getProperties()).containsEntry("available", new BigDecimal("3")));
        verify(transactions, never()).save(any());
    }

    @Test
    void fefoTakesEarliestBatchesFirstAndSplitsTheQuantity() {
        when(balances.lockBatchesForAllocation(1L, 9L, TODAY)).thenReturn(List.of(
                new BatchStock(31L, new BigDecimal("4")), new BatchStock(32L, new BigDecimal("10"))));

        Map<Long, BigDecimal> allocation = service.allocateFefo(low, product, new BigDecimal("6"), false, null);

        assertThat(allocation).containsExactly(Map.entry(31L, new BigDecimal("4")), Map.entry(32L, new BigDecimal("2")));
        verify(balances, never()).lockProductRows(anyLong(), anyLong());
    }

    @Test
    void fefoIncludesExpiredBatchesOnlyWhenAsked() {
        when(balances.lockBatchesForAllocation(1L, 9L, null)).thenReturn(List.of(new BatchStock(30L, BigDecimal.TEN)));

        assertThat(service.allocateFefo(low, product, BigDecimal.TEN, true, null)).containsEntry(30L, BigDecimal.TEN);
    }

    @Test
    void fefoReportsWhatIsAvailableWhenBatchesFallShort() {
        when(balances.lockBatchesForAllocation(1L, 9L, TODAY)).thenReturn(List.of(new BatchStock(31L, new BigDecimal("4"))));

        assertThatThrownBy(() -> service.allocateFefo(low, product, BigDecimal.TEN, false, null))
                .isInstanceOfSatisfying(InsufficientStockException.class,
                        ex -> assertThat(ex.getProperties()).containsEntry("available", new BigDecimal("4")));
    }

    @Test
    void fefoForTransferLocksTheLowerDestinationFirst() {
        when(balances.lockBatchesForAllocation(2L, 9L, TODAY)).thenReturn(List.of(new BatchStock(31L, BigDecimal.TEN)));

        service.allocateFefo(high, product, BigDecimal.ONE, false, low);

        InOrder order = inOrder(balances);
        order.verify(balances).lockProductRows(1L, 9L);
        order.verify(balances).lockBatchesForAllocation(2L, 9L, TODAY);
    }

    private PostingDocument transfer(Warehouse source, Warehouse destination) {
        return new PostingDocument(StockTransactionType.TRANSFER, null, null, null, List.of(
                new PostingDocument.Line(MovementType.TRANSFER, product, null, BigDecimal.TEN, source, destination, null)));
    }

    private static Warehouse warehouse(long id) {
        Warehouse warehouse = new Warehouse("W" + id, "Warehouse " + id, null, null);
        ReflectionTestUtils.setField(warehouse, "id", id);
        return warehouse;
    }
}
