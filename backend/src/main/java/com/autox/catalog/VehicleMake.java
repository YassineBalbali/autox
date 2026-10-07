package com.autox.catalog;

import com.autox.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Manufacturer. Platform-owned reference data, never written by sellers. */
@Entity
@Table(name = "vehicle_make")
public class VehicleMake extends BaseEntity {

    /** Stable URL segment, e.g. {@code mercedes-benz}. Unique. */
    @Column(name = "slug", length = 60, nullable = false)
    private String slug;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @Column(name = "logo_storage_key", length = 512)
    private String logoStorageKey;

    /** Pins the make to the top of the search dropdown. */
    @Column(name = "popular", nullable = false)
    private boolean popular;

    /** {@code false} hides it from new listings but keeps existing ones valid. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected VehicleMake() {
    }

    /** Used by the catalogue import and by tests; sellers never create catalogue rows. */
    public static VehicleMake of(String slug, String name) {
        VehicleMake make = new VehicleMake();
        make.slug = slug;
        make.name = name;
        return make;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLogoStorageKey() {
        return logoStorageKey;
    }

    public void setLogoStorageKey(String logoStorageKey) {
        this.logoStorageKey = logoStorageKey;
    }

    public boolean isPopular() {
        return popular;
    }

    public void setPopular(boolean popular) {
        this.popular = popular;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
