package com.autox.catalog;

import com.autox.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Model line, e.g. "3 Series". Slug is unique within its make. */
@Entity
@Table(name = "vehicle_model")
public class VehicleModel extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "make_id", nullable = false)
    private VehicleMake make;

    @Column(name = "slug", length = 80, nullable = false)
    private String slug;

    @Column(name = "name", length = 120, nullable = false)
    private String name;

    /** Production span, used to bound the year filter. {@code null} = unknown / still produced. */
    @Column(name = "year_from")
    private Short yearFrom;

    @Column(name = "year_to")
    private Short yearTo;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected VehicleModel() {
    }

    public VehicleMake getMake() {
        return make;
    }

    public void setMake(VehicleMake make) {
        this.make = make;
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

    public Short getYearFrom() {
        return yearFrom;
    }

    public void setYearFrom(Short yearFrom) {
        this.yearFrom = yearFrom;
    }

    public Short getYearTo() {
        return yearTo;
    }

    public void setYearTo(Short yearTo) {
        this.yearTo = yearTo;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
