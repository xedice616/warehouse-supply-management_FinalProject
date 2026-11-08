package com.anbar.wms.common.entity;

import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.util.Objects;
import lombok.Getter;
import org.hibernate.proxy.HibernateProxy;

/**
 * Root of every entity: a sequence-generated {@code BIGINT} id and proxy-safe identity semantics.
 *
 * <p>Each table has its own sequence named {@code <table>_seq} with {@code INCREMENT BY 50},
 * matching Hibernate's pooled optimizer so inserts can be batched.
 *
 * <p>Two entities are equal only when they are of the same persistent class and both have the same,
 * non-null id. {@link #hashCode()} is constant per class so an entity keeps its hash bucket
 * when it gets an id on persist.
 */
@Getter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE)
    private Long id;

    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (other == null || effectiveClass(this) != effectiveClass(other)) {
            return false;
        }
        Long otherId = ((BaseEntity) other).getId();
        return id != null && Objects.equals(id, otherId);
    }

    @Override
    public final int hashCode() {
        return effectiveClass(this).hashCode();
    }

    @Override
    public String toString() {
        return effectiveClass(this).getSimpleName() + "(id=" + id + ")";
    }

    /** The mapped class, without initializing a lazy proxy. */
    private static Class<?> effectiveClass(Object entity) {
        return entity instanceof HibernateProxy proxy
                ? proxy.getHibernateLazyInitializer().getPersistentClass()
                : entity.getClass();
    }
}
