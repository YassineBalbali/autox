package com.autox.listing.dto;

import com.autox.listing.LeasingType;
import com.autox.listing.Listing;

/** A leasing offer's terms, wherever the listing is shown; null on a sale. Amounts in minor units. */
public record LeasingView(
        LeasingType type,
        long monthlyPayment,
        long firstPayment,
        int durationMonths,
        int mileagePerYear,
        Long optionPrice,
        long totalCost) {

    public static LeasingView from(Listing listing) {
        return listing.isLeasing()
                ? new LeasingView(listing.getLeasingType(), listing.getLeasingMonthly(),
                        listing.getLeasingFirstPayment(), listing.getLeasingDurationMonths(),
                        listing.getLeasingMileagePerYear(), listing.getLeasingOptionPrice(),
                        listing.getLeasingTotalCost())
                : null;
    }
}
