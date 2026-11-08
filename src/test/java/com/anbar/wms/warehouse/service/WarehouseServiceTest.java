package com.anbar.wms.warehouse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.InactiveResourceException;
import com.anbar.wms.common.exception.ResourceNotFoundException;
import com.anbar.wms.common.exception.StockOnHandException;
import com.anbar.wms.security.WarehouseScope;
import com.anbar.wms.warehouse.dto.WarehouseRequests;
import com.anbar.wms.warehouse.entity.Warehouse;
import com.anbar.wms.common.web.AddressMapperImpl;
import com.anbar.wms.warehouse.mapper.WarehouseMapperImpl;
import com.anbar.wms.warehouse.repository.WarehouseRepository;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class WarehouseServiceTest {

    @Mock
    private WarehouseRepository warehouses;

    @Mock
    private WarehouseStockInfo stockInfo;

    private WarehouseService service;

    @BeforeEach
    void setUp() {
        service = new WarehouseService(warehouses, stockInfo, new WarehouseMapperImpl(new AddressMapperImpl()), new AddressMapperImpl());
    }

    @Test
    void duplicateCodeIsRejected() {
        when(warehouses.existsByCode("BAK-01")).thenReturn(true);

        assertThatThrownBy(() -> service.create(new WarehouseRequests.Create("bak-01", "Baku", null, null)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(warehouses, never()).saveAndFlush(ArgumentMatchers.any());
    }

    @Test
    void warehouseWithStockCannotBeDeactivated() {
        when(warehouses.lockById(7L)).thenReturn(Optional.of(warehouse(7L)));
        when(stockInfo.warehouseHasStockOnHand(7L)).thenReturn(true);

        assertThatThrownBy(() -> service.setActive(7L, false)).isInstanceOf(StockOnHandException.class);
    }

    @Test
    void inactiveWarehouseIsRejectedForNewOperations() {
        Warehouse warehouse = warehouse(7L);
        warehouse.deactivate();
        when(warehouses.findById(7L)).thenReturn(Optional.of(warehouse));

        assertThatThrownBy(() -> service.requireActive(7L)).isInstanceOf(InactiveResourceException.class);
    }

    @Test
    void outOfScopeWarehouseLooksMissing() {
        assertThatThrownBy(() -> service.requireVisible(7L, WarehouseScope.only(Set.of(8L))))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(warehouses, never()).findById(7L);
    }

    @Test
    void administratorSeesEveryWarehouse() {
        when(warehouses.findById(7L)).thenReturn(Optional.of(warehouse(7L)));

        assertThat(service.requireVisible(7L, WarehouseScope.all()).getCode()).isEqualTo("BAK-01");
    }

    private static Warehouse warehouse(long id) {
        Warehouse warehouse = new Warehouse("BAK-01", "Baku", null, null);
        ReflectionTestUtils.setField(warehouse, "id", id);
        return warehouse;
    }
}
