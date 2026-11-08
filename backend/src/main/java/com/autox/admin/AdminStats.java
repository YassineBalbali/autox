package com.autox.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The admin dashboard's figures, for one period, each compared with the period before it, and each
 * split into real activity and demo seed.
 *
 * <p>The split exists because on the public demo nearly every listing is seeded: a total says how
 * big the seed is, not whether anyone uses the site. What the demo seed created is known exactly,
 * from its ledger ({@code demo_seed_ledger}); everything else is real. The page decides whether to
 * add the demo in: both halves are always here.
 *
 * @param generatedAt     when these figures were computed; they are cached for about a minute
 * @param demoDataPresent whether the demo ledger exists, i.e. whether the demo halves can be anything but zero
 * @param period          the period and the one it is compared with
 * @param attention       what is waiting for an administrator, now
 * @param activity        what was created in the period, and in the one before
 * @param series          the same, bucket by bucket: each hour of today, or each day
 * @param listings        listings now, by state
 * @param topMakes        the makes with the most published listings, at most ten, real ones first
 * @param priceVerdicts   published listings by price indicator, by the badge's own rule
 * @param search          how search is used in the period
 * @param emails          transactional e-mail in the period
 */
@Schema(description = "Figures for the admin dashboard over a period, real activity and demo seed apart.")
public record AdminStats(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant generatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean demoDataPresent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PeriodWindow period,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Attention attention,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Activity activity,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Point> series,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ListingsByStatus listings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MakeCount> topMakes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PriceVerdicts priceVerdicts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SearchUsage search,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Emails emails) {

    /** A count, apart: what real users did, and what the demo seed created. */
    @Schema(description = "A count split into real activity and demo seed.")
    public record Split(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long real,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long demo) {

        static final Split ZERO = new Split(0, 0);
    }

    /** A split count in the period, and in the one before it. */
    @Schema(description = "A split count in the period and in the previous one.")
    public record Comparison(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split current,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split previous) {
    }

    /** A count that has no demo half, in the period and in the one before it. */
    @Schema(description = "A count in the period and in the previous one.")
    public record Count(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long current,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long previous) {
    }

    /**
     * The period, in instants, with the one it is compared with. See {@link DashboardPeriod}.
     *
     * @param bucket what one point of {@link AdminStats#series} covers
     */
    @Schema(description = "The period shown and the one it is compared with.")
    public record PeriodWindow(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DashboardPeriod key,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant from,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant previousFrom,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant previousTo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DashboardPeriod.Bucket bucket) {
    }

    /**
     * What is waiting, now, whatever the period.
     *
     * @param pendingModeration  listings in the moderation queue
     * @param oldestPendingReal  when the oldest real one in it was created, if any
     * @param oldestPendingDemo  the same for the seed's
     * @param openReports        reported messages no moderator has dealt with
     * @param oldestOpenReport   when the oldest of those was reported, if any
     * @param openListingReports reported listings' open reports
     * @param oldestOpenListingReport when the oldest of those was filed, if any
     * @param oldestPending      the queue's first listings: the five oldest, and the five oldest real
     *                           ones, which the page chooses between with the demo switch
     */
    @Schema(description = "What is waiting for a moderator, now.")
    public record Attention(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split pendingModeration,
            Instant oldestPendingReal,
            Instant oldestPendingDemo,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long openReports,
            Instant oldestOpenReport,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long openListingReports,
            Instant oldestOpenListingReport,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<PendingListing> oldestPending) {
    }

    /** One listing waiting for review, as the dashboard lists it. */
    @Schema(description = "A listing in the moderation queue.")
    public record PendingListing(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(description = "Make, model and version, as on a result card", requiredMode = Schema.RequiredMode.REQUIRED) String label,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sellerName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sellerRole,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long priceAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currencyCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean leasing,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean damaged,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean demo) {
    }

    /** What was created in the period: listings in any state, accounts, messages, saved searches. */
    @Schema(description = "What was created in the period, compared with the previous one.")
    public record Activity(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Comparison newListings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Comparison registrations,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Comparison messages,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Comparison savedSearches) {
    }

    /**
     * One bucket of the period: an hour, or a day.
     *
     * @param start Paris local time
     */
    @Schema(description = "Activity in one hour or one day of the period.")
    public record Point(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDateTime start,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split newListings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split registrations,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split messages,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split savedSearches,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long emailsSent) {
    }

    /** A make, as the catalogue names it, or as the seller typed it when it is not in the catalogue. */
    @Schema(description = "A make and how many listings of it are published.")
    public record MakeCount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String make,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split published) {
    }

    /**
     * Listings now, by state. Deleted listings are not counted.
     *
     * @param published         online: what a visitor can find
     * @param pendingModeration online and not yet reviewed, a part of {@code published}
     */
    @Schema(description = "Listings by state, now.")
    public record ListingsByStatus(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split published,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split pendingModeration,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split rejected,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split sold,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split expired,
            @Schema(description = "Published and declared damaged: these are also counted in published.",
                    requiredMode = Schema.RequiredMode.REQUIRED) Split damaged,
            @Schema(description = "Published sales. With leasing, adds up to published.",
                    requiredMode = Schema.RequiredMode.REQUIRED) Split sale,
            @Schema(description = "Published leasing offers.",
                    requiredMode = Schema.RequiredMode.REQUIRED) Split leasing) {
    }

    /** Published listings by price indicator; {@code noVerdict} when there are too few comparables. */
    @Schema(description = "Published listings by price indicator.")
    public record PriceVerdicts(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split goodDeal,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split market,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split aboveMarket,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split noVerdict) {
    }

    /**
     * How search is used. Counts only: never what anyone searched for.
     *
     * @param interpretRequests natural-language requests that reached the language model. Stored per
     *                          Paris day, so "today" is compared with the whole of yesterday
     * @param interpretFailed   of those, answered 503
     * @param searches          searches on the results page, first pages only
     * @param topFilters        the filters those searches used most, by name
     * @param activeAlerts      saved searches now, not paused
     */
    @Schema(description = "How search is used in the period: counts only.")
    public record SearchUsage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count interpretRequests,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count interpretFailed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count interpretNotUnderstood,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count searches,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FilterUse> topFilters,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long activeAlerts) {
    }

    /** How many searches used one filter, by its name. */
    @Schema(description = "How many searches used a filter.")
    public record FilterUse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String filter,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long current,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long previous) {
    }

    /**
     * Transactional e-mail. One still being retried is neither sent nor failed.
     *
     * @param sent       handed to the provider, which is not the same as delivered
     * @param failed     given up on after the last attempt, by when it was written
     * @param alertsSent of {@code sent}, saved searches' daily e-mails
     */
    @Schema(description = "Transactional e-mail in the period.")
    public record Emails(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count sent,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count failed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Count alertsSent) {
    }
}
