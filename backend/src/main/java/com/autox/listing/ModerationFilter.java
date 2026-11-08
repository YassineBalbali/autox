package com.autox.listing;

import com.autox.user.UserRole;

/**
 * What a moderation list can be narrowed by. A null or blank value narrows nothing.
 *
 * @param query      words to find in the listing's title, make, model and version, and in its
 *                   seller's display name and e-mail; each word must match one of them
 * @param make       a make, by name, whether the listing names it from the catalogue or typed it
 * @param sellerRole the seller's kind of account
 * @param damaged    damaged vehicles only, or undamaged only
 * @param offer      sale or leasing
 * @param demo       the demo seed's listings only, or real ones only
 * @param priceMin   the cash price at least this, in minor units
 * @param priceMax   the cash price at most this, in minor units
 * @param sort       the order; null for the list's own
 */
public record ModerationFilter(
        String query,
        String make,
        UserRole sellerRole,
        Boolean damaged,
        OfferType offer,
        Boolean demo,
        Long priceMin,
        Long priceMax,
        ModerationSort sort) {
}
