package com.autox.assistant;

import com.autox.listing.CritAir;
import com.autox.listing.ImageVariantType;
import com.autox.listing.LeasingType;
import com.autox.listing.OfferType;
import com.autox.listing.dto.ImageResponse;
import com.autox.search.PriceLevel;
import com.autox.search.dto.PublicListingDetail;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * A listing as a card in an answer, linking to its page. What the listing page shows of it anyway,
 * for the user to see; it is never part of what the planner receives (docs/assistant-plan.md, 1.2).
 *
 * @param photoUrl the first photo's card size, or null
 */
@Schema(name = "AssistantListingCard")
public record ListingCard(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        String photoUrl,
        String make,
        String model,
        int year,
        int mileageKm,
        String fuelType,
        String gearbox,
        long priceAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currencyCode,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OfferType offer,
        LeasingType leasingType,
        Long leasingMonthly,
        boolean damaged,
        PriceLevel verdict,
        CritAir critAir,
        String city,
        String sellerRole) {

    static ListingCard from(PublicListingDetail detail) {
        String photo = detail.images().stream()
                .map(ImageResponse::variants)
                .map(variants -> variants.get(ImageVariantType.CARD))
                .filter(java.util.Objects::nonNull)
                .map(ImageResponse.VariantView::url)
                .findFirst().orElse(null);
        return new ListingCard(
                // What the seller typed, shown in the answer: never with a phone or an address in it.
                detail.id(), ContactMask.strip(detail.title()), photo,
                detail.vehicle().make(), detail.vehicle().model(),
                detail.vehicle().firstRegistrationYear(), detail.mileageKm(),
                detail.vehicle().fuelType(), detail.vehicle().gearbox(),
                detail.priceAmount(), detail.currencyCode(),
                detail.leasing() == null ? OfferType.SALE : OfferType.LEASING,
                detail.leasing() == null ? null : detail.leasing().type(),
                detail.leasing() == null ? null : detail.leasing().monthlyPayment(),
                detail.damage() != null,
                detail.priceIndicator() == null ? null : detail.priceIndicator().level(),
                detail.critAir(),
                ContactMask.strip(detail.location().city()),
                detail.seller().role());
    }
}
