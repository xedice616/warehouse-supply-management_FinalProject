package com.anbar.wms.common.repository;

import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;

/**
 * Repository for data that is written elsewhere (e.g. inventory balances, maintained by atomic SQL in the posting
 * code, or movements, inserted through their transaction). Exposes reads only.
 */
@NoRepositoryBean
public interface ReadOnlyRepository<T, ID> extends Repository<T, ID> {

    Optional<T> findById(ID id);

    Page<T> findAll(Pageable pageable);

    long count();
}
