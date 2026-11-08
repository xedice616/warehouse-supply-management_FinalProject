package com.anbar.wms.common.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class BaseEntityTest {

    static class Product extends BaseEntity {
    }

    static class Warehouse extends BaseEntity {
    }

    @Test
    void transientEntitiesAreOnlyEqualToThemselves() {
        Product a = new Product();
        Product b = new Product();

        assertThat(a).isEqualTo(a).isNotEqualTo(b);
    }

    @Test
    void sameClassAndIdMeansEqual() {
        assertThat(withId(new Product(), 7L)).isEqualTo(withId(new Product(), 7L));
        assertThat(withId(new Product(), 7L)).isNotEqualTo(withId(new Product(), 8L));
    }

    @Test
    void differentClassesWithSameIdAreNotEqual() {
        assertThat(withId(new Product(), 7L)).isNotEqualTo(withId(new Warehouse(), 7L));
    }

    @Test
    void hashCodeSurvivesIdAssignmentOnPersist() {
        Product product = new Product();
        Set<Product> set = new HashSet<>(Set.of(product));

        withId(product, 99L);

        assertThat(set).contains(product);
    }

    private static <T extends BaseEntity> T withId(T entity, Long id) {
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
