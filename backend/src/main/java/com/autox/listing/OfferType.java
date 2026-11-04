package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** What a listing offers. Mirrors ck_listing_offer_type. */
@Schema(enumAsRef = true)
public enum OfferType {
    SALE,
    /** Dealers only. Never a comparable, and never in the same search as a sale. */
    LEASING
}
