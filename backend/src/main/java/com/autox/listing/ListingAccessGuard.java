package com.autox.listing;

import com.autox.api.error.ApiException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The single place that decides whether a caller may act on a listing.
 *
 * <p>Extracted because two services now need it, and a security rule that exists in two
 * copies is a security rule that will eventually exist in two versions.
 */
@Component
public class ListingAccessGuard {

    private final ListingRepository listings;

    public ListingAccessGuard(ListingRepository listings) {
        this.listings = listings;
    }

    /**
     * Loads a listing the caller is entitled to act on: their own, or any if they are an
     * administrator.
     *
     * @throws ApiException 404 when the listing does not exist <em>or</em> belongs to someone
     *     else. Not 403: answering differently for the two cases turns any endpoint taking a
     *     listing id into a way to discover which ids are real.
     */
    public Listing require(UUID callerId, boolean admin, UUID listingId) {
        Listing listing = listings.findActiveById(listingId)
                .orElseThrow(() -> ApiException.notFound("Listing"));

        if (!admin && !listing.getSeller().getId().equals(callerId)) {
            throw ApiException.notFound("Listing");
        }
        return listing;
    }
}
