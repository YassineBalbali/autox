package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** The two kinds of leasing. Mirrors ck_listing_leasing. */
@Schema(enumAsRef = true)
public enum LeasingType {
    /** Location avec option d'achat: consumer credit in France. See docs/leasing.md. */
    LOA,
    /** Location longue durée: a rental, with no option to buy. */
    LLD
}
