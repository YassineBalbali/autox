package com.autox.listing.dto;

import com.autox.listing.Listing;
import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationStatus;
import java.time.Instant;
import java.util.Set;
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
        Instant updatedAt,

        /**
         * The states this listing may move to from where it is — the same field, with the
         * same meaning, as on {@link ListingResponse}.
         *
         * <p>On the summary so that a list of a seller's listings can offer the right
         * actions on each row without the client keeping its own copy of the transition
         * table. A client that derived "can this be paused?" from the status would be
         * correct until the table changed, and then offer a button that answers 409.
         */
        Set<ListingStatus> allowedTransitions,

        /**
         * Whether {@code POST /listings/{id}/renew} would be accepted.
         *
         * <p>Separate from the transitions because renewing a listing that is already
         * live is not a change of state: it stays ACTIVE and its expiry moves.
         */
        boolean renewable,

        /**
         * Whether {@code PATCH /listings/{id}} and the photo endpoints would be accepted.
         * False once a listing is sold or removed: the record of what was sold is kept
         * as it was.
         */
        boolean editable) {

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
                listing.getUpdatedAt(),
                listing.getStatus().allowedTargets(),
                listing.getStatus() == ListingStatus.ACTIVE || listing.getStatus() == ListingStatus.EXPIRED,
                listing.getStatus().isEditable());
    }
}
