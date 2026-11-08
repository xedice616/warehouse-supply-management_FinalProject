package com.anbar.wms.warehouse.entity;

import com.anbar.wms.common.entity.Address;
import com.anbar.wms.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Deliberately has no quantity: a warehouse's stock is its inventory balances, changed only by stock movements. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Warehouse extends AuditableEntity {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Embedded
    private Address address;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WarehouseStatus status;

    public Warehouse(String code, String name, String description, Address address) {
        this.code = normalizeCode(Objects.requireNonNull(code, "code"));
        this.status = WarehouseStatus.ACTIVE;
        update(name, description, address);
    }

    public Warehouse(String code, String name, Address address) {
        this(code, name, null, address);
    }

    public static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    public void update(String name, String description, Address address) {
        this.name = Objects.requireNonNull(name, "name").trim();
        this.description = description;
        this.address = address;
    }

    public boolean isActive() {
        return status == WarehouseStatus.ACTIVE;
    }

    public void activate() {
        status = WarehouseStatus.ACTIVE;
    }

    public void deactivate() {
        status = WarehouseStatus.INACTIVE;
    }
}
