package com.anbar.wms.catalog.entity;

import com.anbar.wms.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UnitOfMeasure extends AuditableEntity {

    public static final int MAX_DECIMAL_SCALE = 4;

    @Column(name = "code", nullable = false, length = 16)
    private String code;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "decimal_scale", nullable = false)
    private int decimalScale;

    @Column(name = "active", nullable = false)
    private boolean active;

    public UnitOfMeasure(String code, String name, int decimalScale) {
        this.code = normalizeCode(Objects.requireNonNull(code, "code"));
        this.active = true;
        update(name, decimalScale);
    }

    public static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    public void update(String name, int decimalScale) {
        if (decimalScale < 0 || decimalScale > MAX_DECIMAL_SCALE) {
            throw new IllegalArgumentException("decimalScale must be between 0 and " + MAX_DECIMAL_SCALE);
        }
        this.name = Objects.requireNonNull(name, "name").trim();
        this.decimalScale = decimalScale;
    }

    public boolean accepts(BigDecimal quantity) {
        return quantity.stripTrailingZeros().scale() <= decimalScale;
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }
}
