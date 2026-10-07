package com.autox.listing;

import com.autox.catalog.BodyType;
import com.autox.catalog.Drivetrain;
import com.autox.catalog.FuelType;
import com.autox.catalog.Gearbox;
import com.autox.catalog.VehicleFeature;
import com.autox.catalog.VehicleMake;
import com.autox.catalog.VehicleModel;
import com.autox.catalog.VehicleTrim;
import com.autox.common.BaseEntity;
import com.autox.reference.Country;
import com.autox.reference.Currency;
import com.autox.reference.GeoPlace;
import com.autox.user.AppUser;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A vehicle advert: the aggregate root of the MVP.
 *
 * <p>Vehicle attributes are stored on the listing itself rather than read through
 * {@link VehicleTrim}, because the seller may correct any of them and because a
 * published advert must keep the values it was published with.
 *
 * <p>{@link #status} and {@link #moderationStatus} are two independent axes: the
 * seller controls the first, an admin the second. A listing is publicly visible
 * only when {@code status = ACTIVE}, {@code moderationStatus != REJECTED} and
 * {@code deletedAt IS NULL} — the condition every partial search index shares.
 */
@Entity
@Table(name = "listing")
public class Listing extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seller_id", nullable = false, updatable = false)
    private AppUser seller;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private ListingStatus status = ListingStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "moderation_status", length = 16, nullable = false)
    private ModerationStatus moderationStatus = ModerationStatus.NOT_REVIEWED;

    @Column(name = "title", length = 140, nullable = false)
    private String title;

    @Column(name = "description")
    private String description;

    /** Minor units of {@link #priceCurrency} (EUR cents). Never a floating-point type. */
    @Column(name = "price_amount", nullable = false)
    private long priceAmount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_currency_code", nullable = false)
    private Currency priceCurrency;

    @Column(name = "price_negotiable", nullable = false)
    private boolean priceNegotiable;

    /** VAT recoverable by a business buyer. Relevant for dealer stock; {@code null} = not stated. */
    @Column(name = "vat_deductible")
    private Boolean vatDeductible;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_code", nullable = false)
    private Country country;

    /** Null while the gazetteer cannot resolve the seller's location; see {@link #postalCode}. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "geo_place_id")
    private GeoPlace geoPlace;

    /** Free-text fallback, used only when {@link #geoPlace} is null. */
    @Column(name = "postal_code", length = 12)
    private String postalCode;

    @Column(name = "city", length = 120)
    private String city;

    /** Copied from {@link #geoPlace} on write so radius search needs no join. */
    @Column(name = "latitude", precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", precision = 9, scale = 6)
    private BigDecimal longitude;

    /** {@code true} = expose only city, postcode and the place centroid. */
    @Column(name = "hide_exact_location", nullable = false)
    private boolean hideExactLocation = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "make_id")
    private VehicleMake make;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_id")
    private VehicleModel model;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trim_id")
    private VehicleTrim trim;

    /** Set only when the catalogue lacks the vehicle; mutually exclusive with {@link #make}. */
    @Column(name = "make_text", length = 80)
    private String makeText;

    @Column(name = "model_text", length = 120)
    private String modelText;

    /** Seller's own version wording, used when no catalogue trim matches. */
    @Column(name = "version_text", length = 160)
    private String versionText;

    /** Stamped when an admin maps a free-text listing onto catalogue rows. */
    @Column(name = "catalog_gap_resolved_at")
    private Instant catalogGapResolvedAt;

    @Column(name = "first_registration_year", nullable = false)
    private short firstRegistrationYear;

    @Column(name = "first_registration_month")
    private Short firstRegistrationMonth;

    /** Always kilometres; mile markets convert in the API layer. */
    @Column(name = "mileage_km", nullable = false)
    private int mileageKm;

    @Enumerated(EnumType.STRING)
    @Column(name = "fuel_type", length = 24, nullable = false)
    private FuelType fuelType;

    @Enumerated(EnumType.STRING)
    @Column(name = "gearbox", length = 24, nullable = false)
    private Gearbox gearbox;

    @Enumerated(EnumType.STRING)
    @Column(name = "body_type", length = 24)
    private BodyType bodyType;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "vehicle_condition", length = 16, nullable = false)
    private VehicleCondition vehicleCondition = VehicleCondition.USED;

    @Enumerated(EnumType.STRING)
    @Column(name = "exterior_color", length = 16)
    private ExteriorColor exteriorColor;

    /** Manufacturer's colour name, e.g. "Bleu Récif". Display only. */
    @Column(name = "color_text", length = 60)
    private String colorText;

    @Column(name = "metallic_paint")
    private Boolean metallicPaint;

    @Enumerated(EnumType.STRING)
    @Column(name = "interior_material", length = 16)
    private InteriorMaterial interiorMaterial;

    @Column(name = "owner_count")
    private Short ownerCount;

    @Column(name = "service_history")
    private Boolean serviceHistory;

    @Column(name = "warranty_months")
    private Short warrantyMonths;

    /** FR: contrôle technique expiry. */
    @Column(name = "technical_inspection_valid_until")
    private LocalDate technicalInspectionValidUntil;

    @Column(name = "co2_emissions_g_km")
    private Short co2EmissionsGKm;

    @Enumerated(EnumType.STRING)
    @Column(name = "emission_class", length = 16)
    private EmissionClass emissionClass;

    /** Optional, never returned by the public API. Used for duplicate detection. */
    @Column(name = "vin", length = 17)
    private String vin;

    /**
     * Updated out of band, not on the read path, to keep detail-page reads free of
     * a write to this row.
     */
    @Column(name = "view_count", nullable = false, updatable = false, insertable = false)
    private long viewCount;

    @Column(name = "published_at")
    private Instant publishedAt;

    /** Listings expire so dead stock leaves search; renewal resets this. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "sold_at")
    private Instant soldAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToMany(mappedBy = "listing", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ListingImage> images = new ArrayList<>();

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "listing_feature",
            joinColumns = @JoinColumn(name = "listing_id"),
            inverseJoinColumns = @JoinColumn(name = "feature_id"))
    private Set<VehicleFeature> features = new LinkedHashSet<>();

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Listing() {
    }

    /** The condition every public query and partial index applies. */
    public boolean isPubliclyVisible() {
        return status == ListingStatus.ACTIVE
                && moderationStatus != ModerationStatus.REJECTED
                && deletedAt == null;
    }

    public void addImage(ListingImage image) {
        image.setListing(this);
        image.setPosition((short) images.size());
        images.add(image);
    }

    public AppUser getSeller() {
        return seller;
    }

    public void setSeller(AppUser seller) {
        this.seller = seller;
    }

    public ListingStatus getStatus() {
        return status;
    }

    public void setStatus(ListingStatus status) {
        this.status = status;
    }

    public ModerationStatus getModerationStatus() {
        return moderationStatus;
    }

    public void setModerationStatus(ModerationStatus moderationStatus) {
        this.moderationStatus = moderationStatus;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public long getPriceAmount() {
        return priceAmount;
    }

    public void setPriceAmount(long priceAmount) {
        this.priceAmount = priceAmount;
    }

    public Currency getPriceCurrency() {
        return priceCurrency;
    }

    public void setPriceCurrency(Currency priceCurrency) {
        this.priceCurrency = priceCurrency;
    }

    public boolean isPriceNegotiable() {
        return priceNegotiable;
    }

    public void setPriceNegotiable(boolean priceNegotiable) {
        this.priceNegotiable = priceNegotiable;
    }

    public Boolean getVatDeductible() {
        return vatDeductible;
    }

    public void setVatDeductible(Boolean vatDeductible) {
        this.vatDeductible = vatDeductible;
    }

    public Country getCountry() {
        return country;
    }

    public void setCountry(Country country) {
        this.country = country;
    }

    public GeoPlace getGeoPlace() {
        return geoPlace;
    }

    public void setGeoPlace(GeoPlace geoPlace) {
        this.geoPlace = geoPlace;
    }

    public String getPostalCode() {
        return postalCode;
    }

    public void setPostalCode(String postalCode) {
        this.postalCode = postalCode;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public void setLatitude(BigDecimal latitude) {
        this.latitude = latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public void setLongitude(BigDecimal longitude) {
        this.longitude = longitude;
    }

    public boolean isHideExactLocation() {
        return hideExactLocation;
    }

    public void setHideExactLocation(boolean hideExactLocation) {
        this.hideExactLocation = hideExactLocation;
    }

    public VehicleMake getMake() {
        return make;
    }

    public void setMake(VehicleMake make) {
        this.make = make;
    }

    public VehicleModel getModel() {
        return model;
    }

    public void setModel(VehicleModel model) {
        this.model = model;
    }

    public VehicleTrim getTrim() {
        return trim;
    }

    public void setTrim(VehicleTrim trim) {
        this.trim = trim;
    }

    public String getMakeText() {
        return makeText;
    }

    public void setMakeText(String makeText) {
        this.makeText = makeText;
    }

    public String getModelText() {
        return modelText;
    }

    public void setModelText(String modelText) {
        this.modelText = modelText;
    }

    public String getVersionText() {
        return versionText;
    }

    public void setVersionText(String versionText) {
        this.versionText = versionText;
    }

    public Instant getCatalogGapResolvedAt() {
        return catalogGapResolvedAt;
    }

    public void setCatalogGapResolvedAt(Instant catalogGapResolvedAt) {
        this.catalogGapResolvedAt = catalogGapResolvedAt;
    }

    public short getFirstRegistrationYear() {
        return firstRegistrationYear;
    }

    public void setFirstRegistrationYear(short firstRegistrationYear) {
        this.firstRegistrationYear = firstRegistrationYear;
    }

    public Short getFirstRegistrationMonth() {
        return firstRegistrationMonth;
    }

    public void setFirstRegistrationMonth(Short firstRegistrationMonth) {
        this.firstRegistrationMonth = firstRegistrationMonth;
    }

    public int getMileageKm() {
        return mileageKm;
    }

    public void setMileageKm(int mileageKm) {
        this.mileageKm = mileageKm;
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

    public BodyType getBodyType() {
        return bodyType;
    }

    public void setBodyType(BodyType bodyType) {
        this.bodyType = bodyType;
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

    public VehicleCondition getVehicleCondition() {
        return vehicleCondition;
    }

    public void setVehicleCondition(VehicleCondition vehicleCondition) {
        this.vehicleCondition = vehicleCondition;
    }

    public ExteriorColor getExteriorColor() {
        return exteriorColor;
    }

    public void setExteriorColor(ExteriorColor exteriorColor) {
        this.exteriorColor = exteriorColor;
    }

    public String getColorText() {
        return colorText;
    }

    public void setColorText(String colorText) {
        this.colorText = colorText;
    }

    public Boolean getMetallicPaint() {
        return metallicPaint;
    }

    public void setMetallicPaint(Boolean metallicPaint) {
        this.metallicPaint = metallicPaint;
    }

    public InteriorMaterial getInteriorMaterial() {
        return interiorMaterial;
    }

    public void setInteriorMaterial(InteriorMaterial interiorMaterial) {
        this.interiorMaterial = interiorMaterial;
    }

    public Short getOwnerCount() {
        return ownerCount;
    }

    public void setOwnerCount(Short ownerCount) {
        this.ownerCount = ownerCount;
    }

    public Boolean getServiceHistory() {
        return serviceHistory;
    }

    public void setServiceHistory(Boolean serviceHistory) {
        this.serviceHistory = serviceHistory;
    }

    public Short getWarrantyMonths() {
        return warrantyMonths;
    }

    public void setWarrantyMonths(Short warrantyMonths) {
        this.warrantyMonths = warrantyMonths;
    }

    public LocalDate getTechnicalInspectionValidUntil() {
        return technicalInspectionValidUntil;
    }

    public void setTechnicalInspectionValidUntil(LocalDate technicalInspectionValidUntil) {
        this.technicalInspectionValidUntil = technicalInspectionValidUntil;
    }

    public Short getCo2EmissionsGKm() {
        return co2EmissionsGKm;
    }

    public void setCo2EmissionsGKm(Short co2EmissionsGKm) {
        this.co2EmissionsGKm = co2EmissionsGKm;
    }

    public EmissionClass getEmissionClass() {
        return emissionClass;
    }

    public void setEmissionClass(EmissionClass emissionClass) {
        this.emissionClass = emissionClass;
    }

    public String getVin() {
        return vin;
    }

    public void setVin(String vin) {
        this.vin = vin;
    }

    public long getViewCount() {
        return viewCount;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getSoldAt() {
        return soldAt;
    }

    public void setSoldAt(Instant soldAt) {
        this.soldAt = soldAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public List<ListingImage> getImages() {
        return images;
    }

    public Set<VehicleFeature> getFeatures() {
        return features;
    }

    public long getVersion() {
        return version;
    }
}
