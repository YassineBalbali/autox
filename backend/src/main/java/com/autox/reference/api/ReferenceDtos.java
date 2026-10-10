package com.autox.reference.api;

import com.autox.catalog.FeatureCategory;
import com.autox.reference.DistanceUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response shapes for the reference endpoints. Grouped in one file because they are small,
 * read-only and only ever used together.
 */
public final class ReferenceDtos {

    private ReferenceDtos() {
    }

    /**
     * One item of equipment.
     *
     * <p>{@code code} is the identity and the only thing other endpoints exchange.
     * {@code labels} is keyed by BCP 47 tag — {@code fr-FR} and {@code en} are seeded. A
     * client that wants a locale with no row falls back to the language-only tag, then to
     * {@code en}, then to the code; the endpoint returns what exists rather than guessing.
     */
    public record Feature(String code, FeatureCategory category, int displayOrder,
                          Map<String, String> labels) {
    }

    public record Make(UUID id, String slug, String name, boolean popular) {
    }

    /** {@code yearFrom}/{@code yearTo} bound the year filter; null means unknown or current. */
    public record Model(UUID id, String slug, String name, Short yearFrom, Short yearTo) {
    }

    /**
     * An open market.
     *
     * <p>No localised country name: {@code code} is an ISO 3166-1 alpha-2 value, and a browser
     * renders it in the user's own language with {@code Intl.DisplayNames} better than a seeded
     * string could. {@code name} is the endonym, for logs and admin screens.
     *
     * <p>{@code currencyMinorUnit} is the one field a price-rendering client cannot do without:
     * every amount in this API is an integer of the minor unit, and dividing by the wrong power
     * of ten is off by a hundred.
     */
    public record Country(String code, String name, String defaultLocale, String phonePrefix,
                          DistanceUnit distanceUnit, String currencyCode, short currencyMinorUnit) {
    }

    /**
     * Every enum a client has to map, keyed by the name the API documents it under.
     *
     * <p>The values, not the labels. Display text belongs in the frontend's own translation
     * files — there is no translation table for enums and inventing one would put French
     * strings in a database that is never the right place to edit them. What the frontend
     * cannot safely duplicate is the <em>set</em> of constants, and that is what this returns:
     * a value appearing here that the frontend has no translation for is a visible gap, where
     * a hardcoded list silently omits it.
     */
    public record Enums(Map<String, List<String>> values) {
    }
}
