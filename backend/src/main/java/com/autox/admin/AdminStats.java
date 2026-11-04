package com.autox.admin;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The admin dashboard's figures, every count split into real activity and demo seed.
 *
 * <p>The split exists because on the public demo nearly every listing is seeded: a total says
 * how big the seed is, not whether anyone uses the site. What the demo seed created is known
 * exactly, from its ledger ({@code demo_seed_ledger}, written by the demo profile); everything
 * else is real. Without the ledger, there is no demo data and every count is real.
 *
 * @param generatedAt     when these figures were computed; they are cached for about a minute
 * @param demoDataPresent whether the demo ledger exists, i.e. whether the demo columns can be
 *                        anything but zero
 * @param listings        listings now, by state
 * @param last7Days       activity in the last 7 × 24 hours
 * @param last30Days      activity in the last 30 × 24 hours
 * @param daily           new listings and registrations per Paris day, the 30 days up to
 *                        today, oldest first, days without any included
 * @param topMakes        the makes with the most published listings, at most ten, by real
 *                        listings first
 * @param priceVerdicts   published listings by price indicator, computed by the same rule as
 *                        the badge on the site
 * @param searchLast7Days natural-language search, the last 7 Paris days including today
 * @param searchLast30Days the same over 30 days
 * @param emailsLast7Days  transactional email written in the last 7 × 24 hours, by outcome
 * @param emailsLast30Days the same over 30 × 24 hours
 * @param activeSavedSearches saved searches now, not paused
 */
@Schema(description = "Figures for the admin dashboard, real activity and demo seed apart.")
public record AdminStats(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant generatedAt,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean demoDataPresent,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ListingsByStatus listings,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Activity last7Days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Activity last30Days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DailyActivity> daily,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MakeCount> topMakes,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PriceVerdicts priceVerdicts,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SearchUsage searchLast7Days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SearchUsage searchLast30Days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmailCounts emailsLast7Days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EmailCounts emailsLast30Days,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long activeSavedSearches) {

    /** A count, apart: what real users did, and what the demo seed created. */
    @Schema(description = "A count split into real activity and demo seed.")
    public record Split(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long real,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long demo) {

        static final Split ZERO = new Split(0, 0);
    }

    /**
     * Listings now, by state. Deleted listings are not counted.
     *
     * @param published         online: what a visitor can find
     * @param pendingModeration online and not yet reviewed: the moderation queue, a part of
     *                          {@code published}
     * @param rejected          refused by a moderator
     * @param sold              marked sold
     * @param expired           expired, not renewed
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

    /** What was created in a period: listings (any state), accounts, messages sent. */
    @Schema(description = "Listings created, accounts registered and messages sent in a period.")
    public record Activity(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split newListings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split registrations,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split messages) {
    }

    @Schema(description = "New listings and registrations on one Paris day.")
    public record DailyActivity(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate day,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split newListings,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split registrations) {
    }

    /** A make, as the catalogue names it, or as the seller typed it when it is not in the catalogue. */
    @Schema(description = "A make and how many listings of it are published.")
    public record MakeCount(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String make,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Split published) {
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
     * Natural-language search in a period. Counts only; query text is never stored.
     *
     * @param requests          requests that reached the language model
     * @param failed            of those, answered 503 (timeout, quota, provider error)
     * @param withNotUnderstood of the answered ones, those with at least one part not understood
     */
    @Schema(description = "Natural-language search usage in a period: counts only.")
    public record SearchUsage(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long requests,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long failed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long withNotUnderstood) {
    }

    /**
     * Transactional email in a period. An email still being retried is neither.
     *
     * @param sent   handed to the provider, which is not the same as delivered
     * @param failed given up on after the last attempt
     * @param alertsSent of {@code sent}, saved searches' daily e-mails
     */
    @Schema(description = "Transactional email in a period, by outcome.")
    public record EmailCounts(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sent,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long failed,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long alertsSent) {
    }
}
