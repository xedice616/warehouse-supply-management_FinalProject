package com.anbar.wms.common.repository;

import java.util.Optional;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * Repository for append-only data (the stock ledger): insert and read, with no update or delete methods at all.
 * Unlike {@code JpaRepository}, there is nothing here a caller could use to change or remove history.
 */
@NoRepositoryBean
public interface AppendOnlyRepository<T, ID> extends Repository<T, ID> {

    /** Inserts a new entity. Entities in an append-only repository are {@code @Immutable}, so this never updates. */
    <S extends T> S save(S entity);

    Optional<T> findById(ID id);

    boolean existsById(ID id);
}
