package com.anbar.wms.identity.repository;

import com.anbar.wms.identity.entity.Role;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Roles are seeded reference data, so this repository only reads. */
public interface RoleRepository extends Repository<Role, Long> {

    Optional<Role> findByCode(String code);

    List<Role> findByCodeIn(Collection<String> codes);

    List<Role> findAllByOrderByCodeAsc();

    /**
     * Locks a role row ({@code SELECT ... FOR UPDATE}) to serialize changes to who holds that role,
     * e.g. so two administrators can't demote each other at the same time and leave none.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Role r where r.code = :code")
    Optional<Role> lockByCode(@Param("code") String code);
}
