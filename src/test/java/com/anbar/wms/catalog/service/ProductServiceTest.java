package com.anbar.wms.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anbar.wms.catalog.dto.CatalogResponses.ProductResponse;
import com.anbar.wms.catalog.dto.ProductRequests;
import com.anbar.wms.catalog.entity.Product;
import com.anbar.wms.catalog.entity.ProductCategory;
import com.anbar.wms.catalog.entity.ProductDetails;
import com.anbar.wms.catalog.entity.UnitOfMeasure;
import com.anbar.wms.catalog.mapper.CatalogMapperImpl;
import com.anbar.wms.catalog.repository.ProductRepository;
import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.StockOnHandException;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository products;

    @Mock
    private CategoryService categories;

    @Mock
    private UnitOfMeasureService units;

    @Mock
    private ProductStockInfo stockInfo;

    private ProductService service;
    private ProductCategory tools;
    private UnitOfMeasure pieces;
    private UnitOfMeasure kilograms;

    @BeforeEach
    void setUp() {
        service = new ProductService(products, categories, units, stockInfo, new CatalogMapperImpl());
        tools = withId(new ProductCategory("Tools", null), 10L);
        pieces = withId(new UnitOfMeasure("PCS", "Piece", 0), 20L);
        kilograms = withId(new UnitOfMeasure("KG", "Kilogram", 3), 21L);
    }

    @Test
    void createsProductWithNormalizedSku() {
        when(categories.requireActive(10L)).thenReturn(tools);
        when(units.requireActive(20L)).thenReturn(pieces);
        when(products.saveAndFlush(any())).thenAnswer(call -> withId(call.getArgument(0), 1L));

        ProductResponse created = service.create(create(" bolt-m8 ", 20L, "100"));

        assertThat(created.sku()).isEqualTo("BOLT-M8");
        assertThat(created.category().name()).isEqualTo("Tools");
        assertThat(created.unit().code()).isEqualTo("PCS");
        assertThat(created.active()).isTrue();
    }

    @Test
    void duplicateSkuIsRejectedBeforeSaving() {
        when(products.existsBySku("BOLT-M8")).thenReturn(true);

        assertThatThrownBy(() -> service.create(create("bolt-m8", 20L, "100")))
                .isInstanceOfSatisfying(DuplicateResourceException.class,
                        ex -> assertThat(ex.getProperties()).containsEntry("field", "sku"));
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void inactiveCategoryCannotBeUsed() {
        when(categories.requireActive(10L)).thenThrow(new InactiveResourceException("Category", 10L, "Tools"));

        assertThatThrownBy(() -> service.create(create("NEW-1", 20L, "1")))
                .isInstanceOf(InactiveResourceException.class);
    }

    @Test
    void minimumStockMustFitTheUnitsDecimalScale() {
        when(categories.requireActive(10L)).thenReturn(tools);
        when(units.requireActive(20L)).thenReturn(pieces);

        assertThatThrownBy(() -> service.create(create("NEW-1", 20L, "2.5")))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_QUANTITY_SCALE));
    }

    @Test
    void staleVersionIsAConflict() {
        Product product = existing(5L);
        when(products.findById(1L)).thenReturn(Optional.of(product));

        assertThatThrownBy(() -> service.update(1L, update(20L, 4L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    void unitCannotChangeOnceStockHasMoved() {
        Product product = existing(5L);
        when(products.findById(1L)).thenReturn(Optional.of(product));
        when(units.requireActive(21L)).thenReturn(kilograms);
        when(stockInfo.productHasStockHistory(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.update(1L, update(21L, 5L)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.STOCK_HISTORY_EXISTS));
    }

    @Test
    void descriptiveFieldsCanChangeDespiteStockHistory() {
        Product product = existing(5L);
        when(products.findById(1L)).thenReturn(Optional.of(product));
        when(products.saveAndFlush(product)).thenReturn(product);

        ProductResponse updated = service.update(1L, update(20L, 5L));

        assertThat(updated.name()).isEqualTo("Renamed bolt");
        verify(stockInfo, never()).productHasStockHistory(1L);
    }

    @Test
    void productWithStockOnHandCannotBeDeactivated() {
        when(products.findById(1L)).thenReturn(Optional.of(existing(5L)));
        when(stockInfo.productHasStockOnHand(1L)).thenReturn(true);

        assertThatThrownBy(() -> service.setActive(1L, false)).isInstanceOf(StockOnHandException.class);
    }

    @Test
    void emptyProductCanBeDeactivatedAndThenIsUnusableForStockOperations() {
        Product product = existing(5L);
        when(products.findById(1L)).thenReturn(Optional.of(product));
        when(products.saveAndFlush(product)).thenReturn(product);

        assertThat(service.setActive(1L, false).active()).isFalse();
        assertThatThrownBy(() -> service.requireActive(1L)).isInstanceOf(InactiveResourceException.class);
    }

    private Product existing(long version) {
        Product product = withId(new Product("BOLT-M8", new ProductDetails("Bolt", null, null, tools, pieces,
                false, false, BigDecimal.TEN, BigDecimal.ONE, null)), 1L);
        ReflectionTestUtils.setField(product, "version", version);
        return product;
    }

    private static ProductRequests.Create create(String sku, long unitId, String minStock) {
        return new ProductRequests.Create(sku, "Hex bolt", null, null, 10L, unitId, false, false,
                new BigDecimal(minStock), new BigDecimal("0.18"), null);
    }

    private static ProductRequests.Update update(long unitId, long version) {
        return new ProductRequests.Update("Renamed bolt", null, null, 10L, unitId, false, false,
                BigDecimal.TEN, new BigDecimal("0.20"), null, version);
    }

    private static <T> T withId(T entity, long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
