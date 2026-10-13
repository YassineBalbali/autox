package com.autox.listing;

import com.autox.api.error.ApiException;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.UserRole;
import org.springframework.stereotype.Component;

/**
 * The cap on how many listings a seller may have online at once.
 *
 * <p>Its own class because two things now put a listing online — a seller publishing and
 * a moderator reinstating — and a cap that exists in two copies is a cap that will
 * eventually have two values.
 *
 * <p>Zero or less means unlimited; a per-account override on the user wins over the role
 * default.
 */
@Component
public class ListingQuotaPolicy {

    private final ListingRepository listings;
    private final AppUserRepository users;
    private final ListingProperties properties;

    public ListingQuotaPolicy(ListingRepository listings, AppUserRepository users, ListingProperties properties) {
        this.listings = listings;
        this.users = users;
        this.properties = properties;
    }

    /** The seller's cap. Zero or less: none. */
    public int limitFor(AppUser seller) {
        if (seller.getActiveListingLimit() != null) {
            return seller.getActiveListingLimit();
        }
        return seller.getRole() == UserRole.PARTICULIER
                ? properties.particulierActiveLimit()
                : properties.professionnelActiveLimit();
    }

    /** How many of the seller's listings are online. Read without a lock: for display. */
    public long activeCount(AppUser seller) {
        return listings.countActiveBySeller(seller.getId());
    }

    /**
     * Whether one more of the seller's listings may go online — <b>holding the seller's row
     * lock from here to the end of the transaction</b>.
     *
     * <p>Counting and then publishing is not safe on its own. At READ COMMITTED two
     * concurrent publications by the same seller both read one below the limit, both pass,
     * and both commit — which is how a cap of five becomes six. A row lock on the seller
     * makes the check and the write atomic <em>for that seller</em>: the second request
     * waits here, and then counts the listing the first one put online.
     *
     * <p>Locking the seller rather than taking a table lock or raising the isolation level
     * keeps the contention where it belongs. Two different sellers never wait for each
     * other, and one seller publishing twice at once is not a case worth optimising.
     *
     * <p>The listing about to go online must not be ACTIVE yet, so that it is not in the
     * count.
     */
    public boolean hasRoomForOneMore(AppUser seller) {
        int limit = limitFor(seller);
        if (limit <= 0) {
            return true;
        }
        users.lockForQuotaCheck(seller.getId());
        return activeCount(seller) < limit;
    }

    /** {@link #hasRoomForOneMore}, as a rule: 409 {@code ACTIVE_LISTING_LIMIT_REACHED} when there is none. */
    public void requireRoomForOneMore(AppUser seller) {
        if (!hasRoomForOneMore(seller)) {
            int limit = limitFor(seller);
            throw ApiException.quotaExceeded(
                    "You already have " + activeCount(seller) + " active listings, which is the maximum of "
                            + limit + " for this account. Pause or remove one first.");
        }
    }
}
