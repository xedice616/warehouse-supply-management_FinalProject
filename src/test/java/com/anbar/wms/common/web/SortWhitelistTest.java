package com.anbar.wms.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

class SortWhitelistTest {

    private final SortWhitelist whitelist = SortWhitelist.of(
            Map.of("sku", "sku", "category", "category.name"),
            Sort.by("sku"));

    @Test
    void appliesDefaultSortWhenNoneRequested() {
        Pageable result = whitelist.apply(PageRequest.of(2, 30));

        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(30);
        assertThat(result.getSort()).isEqualTo(Sort.by("sku"));
    }

    @Test
    void translatesApiNamesToEntityPathsKeepingDirection() {
        Pageable result = whitelist.apply(
                PageRequest.of(0, 10, Sort.by(Sort.Order.desc("category"), Sort.Order.asc("sku"))));

        assertThat(result.getSort()).containsExactly(Sort.Order.desc("category.name"), Sort.Order.asc("sku"));
    }

    @Test
    void rejectsPropertiesOutsideTheWhitelist() {
        assertThatThrownBy(() -> whitelist.apply(PageRequest.of(0, 10, Sort.by("passwordHash"))))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getCode()).isEqualTo(ErrorCode.INVALID_SORT_PROPERTY);
                    assertThat(ex.getProperties()).containsEntry("property", "passwordHash");
                });
    }

    @Test
    void leavesUnpagedRequestsAlone() {
        assertThat(whitelist.apply(Pageable.unpaged())).isEqualTo(Pageable.unpaged());
    }
}
