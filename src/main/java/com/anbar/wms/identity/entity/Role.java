package com.anbar.wms.identity.entity;

import com.anbar.wms.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

/**
 * A security role. Reference data seeded by migration V2; the application never creates or changes roles.
 * The well-known codes are listed in {@link RoleCode}.
 */
@Getter
@Entity
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Role extends BaseEntity {

    @Column(name = "code", nullable = false, length = 50)
    private String code;

    @Column(name = "description", nullable = false, length = 255)
    private String description;
}
