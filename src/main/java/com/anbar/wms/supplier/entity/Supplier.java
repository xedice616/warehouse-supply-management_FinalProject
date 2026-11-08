package com.anbar.wms.supplier.entity;

import com.anbar.wms.common.entity.Address;
import com.anbar.wms.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A company goods are purchased from. Deactivated rather than deleted once anything refers to it. */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Supplier extends AuditableEntity {

    @Column(name = "code", nullable = false, length = 20, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** Taxpayer identification number (VÖEN for Azerbaijani companies). */
    @Column(name = "tax_id", length = 20)
    private String taxId;

    @Column(name = "contact_person", length = 150)
    private String contactPerson;

    @Column(name = "email", length = 254)
    private String email;

    @Column(name = "phone", length = 30)
    private String phone;

    @Embedded
    private Address address;

    @Column(name = "description", length = 1000)
    private String description;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays;

    @Column(name = "active", nullable = false)
    private boolean active;

    public Supplier(String code, String name, String taxId) {
        this(code, new SupplierDetails(name, taxId, null, null, null, null, null, 0));
    }

    public Supplier(String code, SupplierDetails details) {
        this.code = Objects.requireNonNull(code, "code").trim().toUpperCase(Locale.ROOT);
        this.active = true;
        update(details);
    }

    public void update(SupplierDetails details) {
        this.name = Objects.requireNonNull(details.name(), "name").trim();
        this.taxId = details.taxId();
        this.contactPerson = details.contactPerson();
        this.email = details.email() == null ? null : details.email().trim().toLowerCase(Locale.ROOT);
        this.phone = details.phone();
        this.address = details.address();
        this.description = details.description();
        this.paymentTermsDays = details.paymentTermsDays();
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }
}
