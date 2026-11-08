package com.anbar.wms.common.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * Postal address value object, embedded into the owning table (warehouse, supplier).
 * Immutable: replace the whole address to change it. Hibernate loads it as {@code null} when all columns are null.
 *
 * @param countryCode ISO 3166-1 alpha-2, upper-case
 */
@Embeddable
public record Address(
        @Column(name = "address_line", length = 255) String addressLine,
        @Column(name = "city", length = 100) String city,
        @Column(name = "postal_code", length = 20) String postalCode,
        @Column(name = "country_code", length = 2) String countryCode) {
}
