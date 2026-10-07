package com.autox.listing;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Listing policy, bound from {@code autox.listing.*}.
 *
 * <p>The active-listing cap is enforced here and not by a database constraint: no
 * constraint can count a seller's rows, and a trigger that could is worse than the
 * check it replaces.
 */
@ConfigurationProperties(prefix = "autox.listing")
public record ListingProperties(

        /** Days a listing stays live after publication before it expires. */
        int expiryDays,

        /** Simultaneous ACTIVE listings allowed to a private individual. */
        int particulierActiveLimit,

        /** Same for a dealer. {@code 0} means unlimited, pending subscription tiers. */
        int professionnelActiveLimit) {

    public ListingProperties {
        if (expiryDays <= 0) {
            expiryDays = 60;
        }
        if (particulierActiveLimit < 0) {
            particulierActiveLimit = 0;
        }
        if (professionnelActiveLimit < 0) {
            professionnelActiveLimit = 0;
        }
    }

    public Duration expiry() {
        return Duration.ofDays(expiryDays);
    }
}
