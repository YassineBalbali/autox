package com.autox.catalog;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_vehicle_feature_category. Groups equipment in the listing form and detail page. */
@Schema(enumAsRef = true)
public enum FeatureCategory {
    COMFORT,
    SAFETY,
    MULTIMEDIA,
    ASSISTANCE,
    EXTERIOR,
    INTERIOR,
    OTHER
}
