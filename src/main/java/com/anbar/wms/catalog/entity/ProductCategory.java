package com.anbar.wms.catalog.entity;

import com.anbar.wms.common.entity.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductCategory extends AuditableEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ProductCategory parent;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    public ProductCategory(String name, ProductCategory parent) {
        this.active = true;
        update(name, parent);
    }

    /** The caller must ensure {@code parent} is not this category or one of its descendants. */
    public void update(String name, ProductCategory parent) {
        this.name = Objects.requireNonNull(name, "name").trim();
        this.parent = parent;
    }

    public boolean isDescendantOf(ProductCategory candidateAncestor) {
        for (ProductCategory node = parent; node != null; node = node.getParent()) {
            if (node.equals(candidateAncestor)) {
                return true;
            }
        }
        return false;
    }

    public void activate() {
        active = true;
    }

    public void deactivate() {
        active = false;
    }
}
