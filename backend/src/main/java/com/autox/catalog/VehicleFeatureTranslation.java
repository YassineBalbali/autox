package com.autox.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Localised label for a {@link VehicleFeature}. Keyed by (feature, locale). */
@Entity
@Table(name = "vehicle_feature_translation")
public class VehicleFeatureTranslation {

    @EmbeddedId
    private Key key = new Key();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId("featureId")
    @JoinColumn(name = "feature_id", nullable = false)
    private VehicleFeature feature;

    @Column(name = "label", length = 160, nullable = false)
    private String label;

    protected VehicleFeatureTranslation() {
    }

    public Key getKey() {
        return key;
    }

    public VehicleFeature getFeature() {
        return feature;
    }

    public void setFeature(VehicleFeature feature) {
        this.feature = feature;
    }

    /** BCP 47 tag, e.g. {@code fr-FR} or the language-only fallback {@code en}. */
    public String getLocale() {
        return key.locale;
    }

    public void setLocale(String locale) {
        key.locale = locale;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VehicleFeatureTranslation that)) {
            return false;
        }
        return Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(key);
    }

    @Embeddable
    public static class Key implements Serializable {

        private static final long serialVersionUID = 1L;

        @Column(name = "feature_id", nullable = false)
        private UUID featureId;

        @Column(name = "locale", length = 10, nullable = false)
        private String locale;

        public UUID getFeatureId() {
            return featureId;
        }

        public String getLocale() {
            return locale;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return Objects.equals(featureId, that.featureId) && Objects.equals(locale, that.locale);
        }

        @Override
        public int hashCode() {
            return Objects.hash(featureId, locale);
        }
    }
}
