package com.autox.listing.report;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param note optional, except with {@code OTHER}, where it says what is wrong
 */
public record ReportListingRequest(
        @NotNull ListingReportReason reason,
        @Size(max = 1000) String note) {
}
