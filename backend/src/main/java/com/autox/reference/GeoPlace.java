package com.autox.reference;

import com.autox.common.CreatedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Postal-code level gazetteer entry: the unit a seller picks and a buyer searches
 * around. Platform-owned data, seeded per country.
 */
@Entity
@Table(name = "geo_place")
public class GeoPlace extends CreatedEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "country_code", nullable = false)
    private Country country;

    @Column(name = "postal_code", length = 12, nullable = false)
    private String postalCode;

    @Column(name = "city", length = 120, nullable = false)
    private String city;

    /** Lower-cased, accent-stripped {@link #city}. Used for lookup and uniqueness. */
    @Column(name = "city_normalized", length = 120, nullable = false)
    private String cityNormalized;

    /** FR: region. DE: Bundesland. */
    @Column(name = "admin1_code", length = 12)
    private String admin1Code;

    @Column(name = "admin1_name", length = 120)
    private String admin1Name;

    /** FR: departement. DE: Kreis. */
    @Column(name = "admin2_code", length = 12)
    private String admin2Code;

    @Column(name = "admin2_name", length = 120)
    private String admin2Name;

    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    protected GeoPlace() {
    }

    public Country getCountry() {
        return country;
    }

    public void setCountry(Country country) {
        this.country = country;
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

    public String getCityNormalized() {
        return cityNormalized;
    }

    public void setCityNormalized(String cityNormalized) {
        this.cityNormalized = cityNormalized;
    }

    public String getAdmin1Code() {
        return admin1Code;
    }

    public void setAdmin1Code(String admin1Code) {
        this.admin1Code = admin1Code;
    }

    public String getAdmin1Name() {
        return admin1Name;
    }

    public void setAdmin1Name(String admin1Name) {
        this.admin1Name = admin1Name;
    }

    public String getAdmin2Code() {
        return admin2Code;
    }

    public void setAdmin2Code(String admin2Code) {
        this.admin2Code = admin2Code;
    }

    public String getAdmin2Name() {
        return admin2Name;
    }

    public void setAdmin2Name(String admin2Name) {
        this.admin2Name = admin2Name;
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
}
