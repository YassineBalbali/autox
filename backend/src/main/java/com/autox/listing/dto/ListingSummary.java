package com.autox.listing.dto;

import com.autox.listing.Listing;
import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * The row shape for a seller's listing list. Narrow on purpose: a dashboard with fifty
 * rows should not carry fifty VINs and fifty descriptions over a mobile connection.
 */
public record ListingSummary(
        UUID id,
        ListingStatus status,
        ModerationStatus moderationStatus,
        String title,
        long priceAmount,
        String currencyCode,
        String make,
        String model,
        int firstRegistrationYear,
        int mileageKm,
        String city,
        Instant publishedAt,
        Instant expiresAt,
        Instant updatedAt) {

    public static ListingSummary from(Listing listing) {
        return new ListingSummary(
                listing.getId(),
                listing.getStatus(),
                listing.getModerationStatus(),
                listing.getTitle(),
                listing.getPriceAmount(),
                listing.getPriceCurrency().getCode(),
                listing.getMake() == null ? listing.getMakeText() : listing.getMake().getName(),
                listing.getModel() == null ? listing.getModelText() : listing.getModel().getName(),
                listing.getFirstRegistrationYear(),
                listing.getMileageKm(),
                listing.getGeoPlace() == null ? listing.getCity() : listing.getGeoPlace().getCity(),
                listing.getPublishedAt(),
                listing.getExpiresAt(),
                listing.getUpdatedAt());
    }
}
