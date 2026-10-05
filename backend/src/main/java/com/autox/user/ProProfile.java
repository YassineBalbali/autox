package com.autox.user;

import com.autox.reference.GeoPlace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Business data for a PROFESSIONNEL account. Shares its primary key with
 * {@link AppUser}, so the row exists only for dealers and needs no nullable
 * company columns on the account table. Subscription tiers will attach here.
 */
@Entity
@Table(name = "pro_profile")
public class ProProfile {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "user_id")
    private AppUser user;

    @Column(name = "company_name", length = 160, nullable = false)
    private String companyName;

    /** SARL, GmbH, ... */
    @Column(name = "legal_form", length = 60)
    private String legalForm;

    /** FR: SIRET. DE: Handelsregisternummer. Country-specific, hence free text. */
    @Column(name = "registration_number", length = 40)
    private String registrationNumber;

    @Column(name = "vat_number", length = 40)
    private String vatNumber;

    @Column(name = "website_url", length = 255)
    private String websiteUrl;

    @Column(name = "description")
    private String description;

    @Column(name = "logo_storage_key", length = 512)
    private String logoStorageKey;

    @Column(name = "address_line1", length = 160)
    private String addressLine1;

    @Column(name = "address_line2", length = 160)
    private String addressLine2;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "geo_place_id")
    private GeoPlace geoPlace;

    @Column(name = "contact_phone_e164", length = 20)
    private String contactPhoneE164;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected ProProfile() {
    }

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getUserId() {
        return userId;
    }

    public AppUser getUser() {
        return user;
    }

    public void setUser(AppUser user) {
        this.user = user;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getLegalForm() {
        return legalForm;
    }

    public void setLegalForm(String legalForm) {
        this.legalForm = legalForm;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public void setRegistrationNumber(String registrationNumber) {
        this.registrationNumber = registrationNumber;
    }

    public String getVatNumber() {
        return vatNumber;
    }

    public void setVatNumber(String vatNumber) {
        this.vatNumber = vatNumber;
    }

    public String getWebsiteUrl() {
        return websiteUrl;
    }

    public void setWebsiteUrl(String websiteUrl) {
        this.websiteUrl = websiteUrl;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getLogoStorageKey() {
        return logoStorageKey;
    }

    public void setLogoStorageKey(String logoStorageKey) {
        this.logoStorageKey = logoStorageKey;
    }

    public String getAddressLine1() {
        return addressLine1;
    }

    public void setAddressLine1(String addressLine1) {
        this.addressLine1 = addressLine1;
    }

    public String getAddressLine2() {
        return addressLine2;
    }

    public void setAddressLine2(String addressLine2) {
        this.addressLine2 = addressLine2;
    }

    public GeoPlace getGeoPlace() {
        return geoPlace;
    }

    public void setGeoPlace(GeoPlace geoPlace) {
        this.geoPlace = geoPlace;
    }

    public String getContactPhoneE164() {
        return contactPhoneE164;
    }

    public void setContactPhoneE164(String contactPhoneE164) {
        this.contactPhoneE164 = contactPhoneE164;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ProProfile that)) {
            return false;
        }
        return userId != null && Objects.equals(userId, that.userId);
    }

    @Override
    public int hashCode() {
        return userId == null ? System.identityHashCode(this) : userId.hashCode();
    }
}
