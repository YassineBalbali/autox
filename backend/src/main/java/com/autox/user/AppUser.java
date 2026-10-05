package com.autox.user;

import com.autox.common.BaseEntity;
import com.autox.reference.Country;
import com.autox.reference.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Account. One table for all three roles: they share authentication, contact and
 * locale data, and a user can be promoted from PARTICULIER to PROFESSIONNEL
 * without moving rows. Role-specific business data lives in {@link ProProfile}.
 *
 * <p>Table is {@code app_user} because {@code user} is reserved in PostgreSQL.
 */
@Entity
@Table(name = "app_user")
public class AppUser extends BaseEntity {

    /** Unique case-insensitively, via a {@code lower(email)} index. */
    @Column(name = "email", length = 255, nullable = false)
    private String email;

    @Column(name = "password_hash", length = 100, nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", length = 16, nullable = false)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 24, nullable = false)
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    /** Shown on listings: the person's chosen name, or the garage's trading name. */
    @Column(name = "display_name", length = 120, nullable = false)
    private String displayName;

    @Column(name = "first_name", length = 80)
    private String firstName;

    @Column(name = "last_name", length = 80)
    private String lastName;

    /** E.164, e.g. {@code +33612345678}. */
    @Column(name = "phone_e164", length = 20)
    private String phoneE164;

    @Column(name = "phone_verified_at")
    private Instant phoneVerifiedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_code", nullable = false)
    private Country country;

    @Column(name = "locale", length = 10, nullable = false)
    private String locale;

    /** Overrides the country default for display. Prices are stored in the listing's currency. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "preferred_currency_code")
    private Currency preferredCurrency;

    /** Per-account override; {@code null} = use the role default from configuration. */
    @Column(name = "active_listing_limit")
    private Short activeListingLimit;

    @Column(name = "email_verified_at")
    private Instant emailVerifiedAt;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** Soft delete: listings and moderation history must survive account closure. */
    @Column(name = "deleted_at")
    private Instant deletedAt;

    @OneToOne(mappedBy = "user", fetch = FetchType.LAZY)
    private ProProfile proProfile;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected AppUser() {
    }

    /**
     * A newly registered account. Everything a row cannot exist without is a
     * parameter; everything else is set afterwards, so no caller can build a user
     * that violates the table's NOT NULL constraints.
     */
    public static AppUser register(String email, String passwordHash, UserRole role,
                                   String displayName, Country country, String locale) {
        AppUser user = new AppUser();
        user.email = email;
        user.passwordHash = passwordHash;
        user.role = role;
        user.displayName = displayName;
        user.country = country;
        user.locale = locale;
        user.status = UserStatus.PENDING_VERIFICATION;
        return user;
    }

    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public UserRole getRole() {
        return role;
    }

    public void setRole(UserRole role) {
        this.role = role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public void setStatus(UserStatus status) {
        this.status = status;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getPhoneE164() {
        return phoneE164;
    }

    public void setPhoneE164(String phoneE164) {
        this.phoneE164 = phoneE164;
    }

    public Instant getPhoneVerifiedAt() {
        return phoneVerifiedAt;
    }

    public void setPhoneVerifiedAt(Instant phoneVerifiedAt) {
        this.phoneVerifiedAt = phoneVerifiedAt;
    }

    public Country getCountry() {
        return country;
    }

    public void setCountry(Country country) {
        this.country = country;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public Currency getPreferredCurrency() {
        return preferredCurrency;
    }

    public void setPreferredCurrency(Currency preferredCurrency) {
        this.preferredCurrency = preferredCurrency;
    }

    public Short getActiveListingLimit() {
        return activeListingLimit;
    }

    public void setActiveListingLimit(Short activeListingLimit) {
        this.activeListingLimit = activeListingLimit;
    }

    public Instant getEmailVerifiedAt() {
        return emailVerifiedAt;
    }

    public void setEmailVerifiedAt(Instant emailVerifiedAt) {
        this.emailVerifiedAt = emailVerifiedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Instant lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public void setDeletedAt(Instant deletedAt) {
        this.deletedAt = deletedAt;
    }

    public ProProfile getProProfile() {
        return proProfile;
    }

    public void setProProfile(ProProfile proProfile) {
        this.proProfile = proProfile;
    }

    public long getVersion() {
        return version;
    }
}
