package com.autox.listing.dto;

/**
 * How many listings the caller has online, against how many they may have.
 *
 * <p>The rule is enforced when a listing is published, whatever a client believes; this
 * exists so that a client can say so <em>before</em> the seller has spent ten minutes on a
 * listing they will not be allowed to put online.
 *
 * @param active     listings currently online — the only ones that count
 * @param limit      the most this account may have online, or {@code null} for no limit
 * @param canPublish whether one more could go online now. Advisory: publishing checks
 *                   again, under a lock, and is the only answer that counts
 */
public record ListingQuota(long active, Integer limit, boolean canPublish) {
}
