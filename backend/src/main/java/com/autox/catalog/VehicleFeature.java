package com.autox.catalog;

import com.autox.common.UuidV7;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * One item of equipment a listing can declare. The {@link #code} is the stable,
 * language-neutral identity; human labels live in {@link VehicleFeatureTranslation}
 * so a new market adds rows rather than columns.
 */
@Entity
@Table(name = "vehicle_feature")
public class VehicleFeature {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** UPPER_SNAKE_CASE, unique, e.g. {@code ADAPTIVE_CRUISE}. */
    @Column(name = "code", length = 60, nullable = false)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", length = 24, nullable = false)
    private FeatureCategory category;

    @Column(name = "display_order", nullable = false)
    private short displayOrder;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "feature", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<VehicleFeatureTranslation> translations = new LinkedHashSet<>();

    protected VehicleFeature() {
    }

    @PrePersist
    void onPersist() {
        if (id == null) {
            id = UuidV7.generate();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public FeatureCategory getCategory() {
        return category;
    }

    public void setCategory(FeatureCategory category) {
        this.category = category;
    }

    public short getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(short displayOrder) {
        this.displayOrder = displayOrder;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Set<VehicleFeatureTranslation> getTranslations() {
        return translations;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VehicleFeature that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? System.identityHashCode(this) : id.hashCode();
    }
}
