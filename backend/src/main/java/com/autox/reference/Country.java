package com.autox.reference;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Objects;

/** A market. ISO 3166-1 alpha-2 code as the primary key. */
@Entity
@Table(name = "country")
public class Country {

    @Id
    @Column(name = "code", length = 2, nullable = false, updatable = false)
    private String code;

    @Column(name = "name", length = 80, nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "default_currency_code", nullable = false)
    private Currency defaultCurrency;

    /** BCP 47 tag, e.g. {@code fr-FR}. */
    @Column(name = "default_locale", length = 10, nullable = false)
    private String defaultLocale;

    @Column(name = "phone_prefix", length = 8, nullable = false)
    private String phonePrefix;

    /** Display unit only; mileage is always persisted in kilometres. */
    @Enumerated(EnumType.STRING)
    @Column(name = "distance_unit", length = 8, nullable = false)
    private DistanceUnit distanceUnit = DistanceUnit.KM;

    /** {@code false} = known country, not yet open for listings. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected Country() {
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Currency getDefaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(Currency defaultCurrency) {
        this.defaultCurrency = defaultCurrency;
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    public void setDefaultLocale(String defaultLocale) {
        this.defaultLocale = defaultLocale;
    }

    public String getPhonePrefix() {
        return phonePrefix;
    }

    public void setPhonePrefix(String phonePrefix) {
        this.phonePrefix = phonePrefix;
    }

    public DistanceUnit getDistanceUnit() {
        return distanceUnit;
    }

    public void setDistanceUnit(DistanceUnit distanceUnit) {
        this.distanceUnit = distanceUnit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Country that)) {
            return false;
        }
        return Objects.equals(code, that.code);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(code);
    }
}
