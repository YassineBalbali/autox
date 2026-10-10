package com.autox.listing;

/** Mirrors ck_listing_price_history_reason. */
public enum PriceChangeReason {

    /** The price the listing was first published at. There is exactly one per listing. */
    INITIAL,

    /** The seller changed the price — or the currency — on a listing that was already public. */
    SELLER_EDIT
}
