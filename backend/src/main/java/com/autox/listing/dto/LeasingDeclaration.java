package com.autox.listing.dto;

import com.autox.listing.LeasingType;
import com.autox.listing.OfferType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Whether a listing is a sale or a leasing offer, and its terms. There is no total here: the server
 * computes it, so it cannot be typed.
 *
 * @param monthlyPayment TTC, in minor units
 * @param firstPayment   apport or premier loyer, in minor units; may be zero
 * @param optionPrice    LOA only, in minor units
 */
public record LeasingDeclaration(
        @NotNull OfferType offerType,
        LeasingType type,
        @Min(1) @Max(100_000_000) Long monthlyPayment,
        @Min(0) @Max(1_000_000_000) Long firstPayment,
        @Min(6) @Max(120) Integer durationMonths,
        @Min(1000) @Max(200_000) Integer mileagePerYear,
        @Min(0) @Max(1_000_000_000) Long optionPrice) {

    @Schema(hidden = true)
    @AssertTrue(message = "a leasing offer needs its type, monthly payment, first payment, duration and mileage")
    public boolean isComplete() {
        return offerType != OfferType.LEASING
                || (type != null && monthlyPayment != null && firstPayment != null
                        && durationMonths != null && mileagePerYear != null);
    }

    @Schema(hidden = true)
    @AssertTrue(message = "an LOA needs a purchase option price, and an LLD has none")
    public boolean isOptionPriceConsistent() {
        return offerType != OfferType.LEASING
                || type == null
                || (type == LeasingType.LOA) == (optionPrice != null);
    }
}
