package com.autox.search.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One result card.
 *
 * <p>Every enumerated value is the stable code, never a display string: {@code "DIESEL"}, not
 * "Diesel" and certainly not "Gazole". The French frontend maps codes to French; nothing here
 * assumes a language.
 *
 * <p>No seller contact details, no VIN, no moderation state. A search result is public, and a
 * scraper gets exactly what a buyer's screen shows.
 */
public record PublicListingSummary(
        UUID id,
        String title,

        long priceAmount,
        String currencyCode,
        boolean priceNegotiable,

        String make,
        String model,
        String version,
        int firstRegistrationYear,
        Integer firstRegistrationMonth,
        int mileageKm,

        /** Stable codes: FuelType, Gearbox, BodyType, Drivetrain, VehicleCondition. */
        String fuelType,
        String gearbox,
        String bodyType,
        String drivetrain,
        Integer powerHp,
        String condition,

        String countryCode,
        String city,
        String postalCode,

        SellerCard seller,
        Map<String, ImageVariantView> coverImage,
        /** How many photos the listing shows: confirmed uploads only, the cover included. */
        int photoCount,

        Instant publishedAt) {

    /** What a buyer sees about who is selling: a name and whether it is a trade seller. */
    public record SellerCard(UUID id, String displayName, String role) {
    }

    public record ImageVariantView(String url, int width, int height) {
    }
}
