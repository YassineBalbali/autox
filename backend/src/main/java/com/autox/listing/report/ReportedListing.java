package com.autox.listing.report;

import com.autox.user.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/**
 * An open report on a listing, for a moderator: why, by whom, and the listing as it is now.
 *
 * @param listingStatus     the listing's status now: a report on one already offline is dismissed
 * @param leasingMonthly    the monthly payment of a leasing offer, in minor units; null for a sale
 * @param openOnListing     how many open reports the listing has, this one included
 */
public record ReportedListing(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reportId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ListingReportReason reason,
        String note,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant reportedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID listingId,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String listingStatus,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long priceAmount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currencyCode,
        Long leasingMonthly,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Party seller,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Party reporter,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int openOnListing) {

    public record Party(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UserRole role) {
    }

    static ReportedListing from(ListingReportRepository.Row row) {
        return new ReportedListing(row.id(), row.reason(), row.note(), row.reportedAt(), row.listingId(), row.title(),
                row.status(), row.priceAmount(), row.currencyCode(), row.leasingMonthly(),
                new Party(row.seller().id(), row.seller().displayName(), row.seller().role()),
                new Party(row.reporter().id(), row.reporter().displayName(), row.reporter().role()),
                row.openOnListing());
    }
}
