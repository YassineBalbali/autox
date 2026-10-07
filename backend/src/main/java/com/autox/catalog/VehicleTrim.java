package com.autox.catalog;

import com.autox.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A concrete factory variant, e.g. "320d xDrive M Sport". Optional on a listing.
 *
 * <p>Its technical attributes are defaults offered to the seller when they pick a
 * trim; the values the seller confirms are copied onto the listing, so a later
 * catalogue correction never silently rewrites a published advert.
 */
@Entity
@Table(name = "vehicle_trim")
public class VehicleTrim extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "model_id", nullable = false)
    private VehicleModel model;

    @Column(name = "slug", length = 120, nullable = false)
    private String slug;

    @Column(name = "name", length = 160, nullable = false)
    private String name;

    @Column(name = "year_from")
    private Short yearFrom;

    @Column(name = "year_to")
    private Short yearTo;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_type", length = 24)
    private BodyType bodyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", length = 24)
    private FuelType fuelType;

    @Enumerated(EnumType.STRING)
    @Column(name = "gearbox", length = 24)
    private Gearbox gearbox;

    @Enumerated(EnumType.STRING)
    @Column(name = "drivetrain", length = 8)
    private Drivetrain drivetrain;

    @Column(name = "power_hp")
    private Short powerHp;

    @Column(name = "power_kw")
    private Short powerKw;

    @Column(name = "engine_displacement_cc")
    private Integer engineDisplacementCc;

    @Column(name = "doors")
    private Short doors;

    @Column(name = "seats")
    private Short seats;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    protected VehicleTrim() {
    }

    /** Used by the catalogue import and by tests. */
    public static VehicleTrim of(VehicleModel model, String slug, String name) {
        VehicleTrim trim = new VehicleTrim();
        trim.model = model;
        trim.slug = slug;
        trim.name = name;
        return trim;
    }

    public VehicleModel getModel() {
        return model;
    }

    public void setModel(VehicleModel model) {
        this.model = model;
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

    public BodyType getBodyType() {
        return bodyType;
    }

    public void setBodyType(BodyType bodyType) {
        this.bodyType = bodyType;
    }

    public FuelType getFuelType() {
        return fuelType;
    }

    public void setFuelType(FuelType fuelType) {
        this.fuelType = fuelType;
    }

    public Gearbox getGearbox() {
        return gearbox;
    }

    public void setGearbox(Gearbox gearbox) {
        this.gearbox = gearbox;
    }

    public Drivetrain getDrivetrain() {
        return drivetrain;
    }

    public void setDrivetrain(Drivetrain drivetrain) {
        this.drivetrain = drivetrain;
    }

    public Short getPowerHp() {
        return powerHp;
    }

    public void setPowerHp(Short powerHp) {
        this.powerHp = powerHp;
    }

    public Short getPowerKw() {
        return powerKw;
    }

    public void setPowerKw(Short powerKw) {
        this.powerKw = powerKw;
    }

    public Integer getEngineDisplacementCc() {
        return engineDisplacementCc;
    }

    public void setEngineDisplacementCc(Integer engineDisplacementCc) {
        this.engineDisplacementCc = engineDisplacementCc;
    }

    public Short getDoors() {
        return doors;
    }

    public void setDoors(Short doors) {
        this.doors = doors;
    }

    public Short getSeats() {
        return seats;
    }

    public void setSeats(Short seats) {
        this.seats = seats;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
