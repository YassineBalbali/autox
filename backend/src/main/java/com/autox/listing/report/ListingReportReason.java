package com.autox.listing.report;

import com.autox.listing.ModerationReason;
import io.swagger.v3.oas.annotations.media.Schema;

/** Why someone reports a listing. Mirrors {@code ck_listing_report_reason}. */
@Schema(enumAsRef = true)
public enum ListingReportReason {
    /** Arnaque. */
    SCAM(ModerationReason.SUSPECTED_FRAUD),
    /** Annonce trompeuse. */
    MISLEADING(ModerationReason.MISLEADING_INFORMATION),
    /** Contenu inapproprié. */
    INAPPROPRIATE(ModerationReason.PROHIBITED_CONTENT),
    /** Autre: a note is required. */
    OTHER(ModerationReason.OTHER);

    private final ModerationReason moderationReason;

    ListingReportReason(ModerationReason moderationReason) {
        this.moderationReason = moderationReason;
    }

    /** The reason recorded when a moderator takes the listing down over this report. */
    public ModerationReason moderationReason() {
        return moderationReason;
    }
}
