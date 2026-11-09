package com.autox.dealer;

import java.time.Instant;
import java.util.UUID;

/**
 * A professional's public page.
 *
 * <p>The way to reach them is AutoX's messaging, and a phone number only when they chose to show
 * one. Never their e-mail address: it is not in this record, so it cannot be shown by mistake.
 *
 * @param sellerId     the account, for the listings: the search with {@code sellerId} set
 * @param companyName  the registered name, when it differs from the name shown
 * @param websiteUrl   http or https only
 * @param phone        the company's contact number, only when the dealer chose to show it
 * @param demo         one of the demo seed's dealers, which nobody answers
 * @param own          the caller's own page: they can see it hidden, and cannot write to themselves
 * @param hiddenAt     set while an administrator has hidden the page; only the dealer and
 *                     administrators are shown a hidden page, and only they get this and the reason
 */
public record DealerPage(
        String slug,
        UUID sellerId,
        String name,
        String companyName,
        String logoUrl,
        String city,
        String postalCode,
        String departement,
        String description,
        String openingHours,
        String websiteUrl,
        String phone,
        Instant memberSince,
        int saleListings,
        int leasingListings,
        boolean demo,
        boolean own,
        Instant hiddenAt,
        String hiddenReason) {
}
