package com.anbar.wms.identity.repository;

import com.anbar.wms.identity.entity.AppUser;
import com.anbar.wms.identity.entity.UserStatus;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Usernames and emails are stored lower-case; pass normalized values. */
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByUsername(String username);

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findByEmail(String email);

    @EntityGraph(attributePaths = "roles")
    Optional<AppUser> findWithRolesById(Long id);

    @EntityGraph(attributePaths = {"roles", "warehouses"})
    Optional<AppUser> findWithRolesAndWarehousesById(Long id);

    @Query(value = """
            select distinct u from AppUser u join u.warehouses w join u.roles r
            where w.id = :warehouseId and r.code = :roleCode""",
            countQuery = """
            select count(distinct u) from AppUser u join u.warehouses w join u.roles r
            where w.id = :warehouseId and r.code = :roleCode""")
    Page<AppUser> findByWarehouseAndRole(@Param("warehouseId") Long warehouseId, @Param("roleCode") String roleCode,
                                         Pageable pageable);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    @Query("""
            select count(distinct u) from AppUser u join u.roles r
            where r.code = :roleCode and u.status = :status""")
    long countByRoleAndStatus(@Param("roleCode") String roleCode, @Param("status") UserStatus status);

    /**
     * Counts a failed login atomically (no lost increments under concurrent attempts). Reaching
     * {@code maxAttempts} locks the account until {@code lockUntil} and restarts the count.
     * Deliberately bypasses {@code @Version}: security counters must not conflict with profile edits.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update AppUser u set
                u.lockedUntil = case when u.failedLoginAttempts + 1 >= :maxAttempts then :lockUntil else u.lockedUntil end,
                u.failedLoginAttempts = case when u.failedLoginAttempts + 1 >= :maxAttempts then 0 else u.failedLoginAttempts + 1 end
            where u.id = :id""")
    int recordFailedLogin(@Param("id") Long id, @Param("maxAttempts") int maxAttempts,
                          @Param("lockUntil") Instant lockUntil);

    @Modifying(clearAutomatically = true)
    @Query("update AppUser u set u.failedLoginAttempts = 0, u.lockedUntil = null, u.lastLoginAt = :now where u.id = :id")
    int recordSuccessfulLogin(@Param("id") Long id, @Param("now") Instant now);
}
