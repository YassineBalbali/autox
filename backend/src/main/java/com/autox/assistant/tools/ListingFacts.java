package com.autox.assistant.tools;

import com.autox.listing.CritAir;
import com.autox.listing.DamageType;
import com.autox.listing.OfferType;
import com.autox.listing.dto.LeasingView;
import com.autox.search.PriceLevel;
import com.autox.search.dto.PublicListingDetail;
import java.util.List;
import java.util.UUID;

/**
 * A listing as the assistant may know it: catalogue fields, codes and figures, and nothing a
 * seller typed freely.
 *
 * <p><b>Left out on purpose</b> (docs/assistant-plan.md, 1.2 and 3.2): the title, the description,
 * the version text, a make or model typed as text instead of chosen from the catalogue, the
 * damage description, the seller's name and any other detail about them, the VIN and the
 * photos. Seller-written text is how a listing could carry instructions to a model, and the
 * seller's identity is not the assistant's to repeat. {@code ListingFactsTest} holds the fields
 * to an allow-list, so adding one is a decision made in review.
 *
 * @param make         the catalogue make; null when the seller typed it as text
 * @param model        the catalogue model; null when typed as text
 * @param damageTypes  null when the car is not declared damaged
 * @param leasing      null on a sale; the offer's own terms, all computed or chosen, on a lease
 * @param verdict      the price indicator; null without enough comparables, on a lease or new car
 * @param critAir      estimated from fuel and first registration; null when that cannot settle it
 * @param city         for a card under the listing; the gazetteer's name, or what the seller typed
 *                     when the postal code is not in it, so like the title it is never put in a prompt
 * @param sellerRole   {@code PARTICULIER} or {@code PROFESSIONNEL}, nothing more about the seller
 */
@io.swagger.v3.oas.annotations.media.Schema(name = "AssistantListingFacts")
public record ListingFacts(
        UUID id,
        String make,
        String model,
        int year,
        Integer month,
        int mileageKm,
        String fuelType,
        String gearbox,
        String bodyType,
        Integer powerHp,
        long priceAmount,
        String currencyCode,
        OfferType offer,
        LeasingView leasing,
        String condition,
        boolean soldNew,
        List<DamageType> damageTypes,
        Boolean damageRunning,
        PriceLevel verdict,
        CritAir critAir,
        String city,
        String sellerRole) {

    static ListingFacts from(PublicListingDetail detail) {
        PublicListingDetail.VehicleView vehicle = detail.vehicle();
        boolean catalogued = vehicle.makeId() != null;
        return new ListingFacts(
                detail.id(),
                catalogued ? vehicle.make() : null,
                vehicle.modelId() != null ? vehicle.model() : null,
                vehicle.firstRegistrationYear(),
                vehicle.firstRegistrationMonth(),
                detail.mileageKm(),
                vehicle.fuelType(),
                vehicle.gearbox(),
                vehicle.bodyType(),
                vehicle.powerHp(),
                detail.priceAmount(),
                detail.currencyCode(),
                detail.leasing() == null ? OfferType.SALE : OfferType.LEASING,
                detail.leasing(),
                detail.condition(),
                detail.soldNew(),
                detail.damage() == null ? null : detail.damage().types(),
                detail.damage() == null ? null : detail.damage().running(),
                detail.priceIndicator() == null ? null : detail.priceIndicator().level(),
                detail.critAir(),
                com.autox.assistant.ContactMask.strip(detail.location().city()),
                detail.seller().role());
    }

    public boolean damaged() {
        return damageTypes != null;
    }

    /** What the financing simulator applies to: a sale, not damaged, with a price (the listing page's rule). */
    public boolean financeable() {
        return offer == OfferType.SALE && !damaged() && priceAmount > 0;
    }
}
