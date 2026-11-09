package com.autox.dealer;

import java.time.Instant;

/**
 * One professional in the directory.
 *
 * @param logoUrl         null without a logo: the page shows the name's initials instead
 * @param city            where the dealer is: their company's place, otherwise where most of their
 *                        listings online are; null when neither says
 * @param departement     read from the postal code: "33", "974", "2A"
 * @param saleListings    listings for sale online now
 * @param leasingListings leasing offers online now
 * @param memberSince     when the account was created
 */
public record DealerCard(
        String slug,
        String name,
        String logoUrl,
        String city,
        String postalCode,
        String departement,
        int saleListings,
        int leasingListings,
        Instant memberSince) {
}
