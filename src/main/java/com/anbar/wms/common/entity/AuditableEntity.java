package com.anbar.wms.common.entity;

import com.anbar.wms.common.exception.BusinessException;
import com.anbar.wms.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import lombok.Getter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Base for mutable entities (master data, purchase orders, counts): optimistic locking plus who/when auditing.
 *
 * <p>Expected columns: {@code version BIGINT NOT NULL}, {@code created_at TIMESTAMPTZ NOT NULL},
 * {@code created_by VARCHAR(100) NOT NULL}, {@code updated_at TIMESTAMPTZ NOT NULL},
 * {@code updated_by VARCHAR(100) NOT NULL}.
 *
 * <p>Immutable ledger entities (stock transactions and movements) do not extend this class:
 * they are never updated, so they need neither a version nor "last modified" columns.
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class AuditableEntity extends BaseEntity {

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 100)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @LastModifiedBy
    @Column(name = "updated_by", nullable = false, length = 100)
    private String updatedBy;

    /**
     * Rejects an update based on a stale read. Hibernate's own version check at flush still covers the race
     * between this check and the commit.
     */
    public void verifyVersion(Long expectedVersion) {
        if (!Objects.equals(version, expectedVersion)) {
            throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
        }
    }
}
