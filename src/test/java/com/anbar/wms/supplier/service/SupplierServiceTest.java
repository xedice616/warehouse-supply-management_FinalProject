package com.anbar.wms.supplier.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.DuplicateResourceException;
import com.anbar.wms.common.exception.ErrorCode;
import com.anbar.wms.common.web.AddressDto;
import com.anbar.wms.common.web.AddressMapperImpl;
import com.anbar.wms.supplier.dto.SupplierRequests;
import com.anbar.wms.supplier.dto.SupplierResponse;
import com.anbar.wms.supplier.mapper.SupplierMapperImpl;
import com.anbar.wms.supplier.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SupplierServiceTest {

    @Mock
    private SupplierRepository suppliers;

    private SupplierService service;

    @BeforeEach
    void setUp() {
        service = new SupplierService(suppliers, new SupplierMapperImpl(new AddressMapperImpl()), new AddressMapperImpl());
    }

    @Test
    void createsSupplierWithGeneratedCodeAndNormalizedFields() {
        when(suppliers.nextCodeNumber()).thenReturn(7L);
        when(suppliers.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        SupplierResponse created = service.create(request("1234567890", "AZ", " Sales@Example.COM "));

        assertThat(created.code()).isEqualTo("SUP-000007");
        assertThat(created.email()).isEqualTo("sales@example.com");
        assertThat(created.address().countryCode()).isEqualTo("AZ");
        assertThat(created.active()).isTrue();
    }

    @Test
    void duplicateTaxIdIsRejected() {
        when(suppliers.existsByTaxId("1234567890")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("1234567890", "AZ", null)))
                .isInstanceOf(DuplicateResourceException.class);
        verify(suppliers, never()).saveAndFlush(any());
    }

    @Test
    void azerbaijaniTaxIdMustBeTenDigits() {
        assertThatThrownBy(() -> service.create(request("AB12345", "AZ", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
    }

    @Test
    void foreignTaxIdFollowsTheGenericRule() {
        when(suppliers.nextCodeNumber()).thenReturn(8L);
        when(suppliers.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(service.create(request("de123456789", "DE", null)).taxIdentificationNumber()).isEqualTo("DE123456789");
    }

    private static SupplierRequests.Create request(String taxId, String country, String email) {
        return new SupplierRequests.Create("Caspian Fasteners", taxId, null, email, null,
                new AddressDto(null, "City", null, country), null, 30);
    }
}
