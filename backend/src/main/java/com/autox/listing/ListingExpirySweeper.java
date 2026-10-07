package com.autox.listing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves listings past their expiry date to EXPIRED.
 *
 * <p>Without this, {@code expires_at} would be a date nothing acts on and EXPIRED would be
 * unreachable. Expiry is deliberately a stored state rather than a computed one, so a
 * seller's dashboard, the quota count and search all agree on what is live without each
 * re-deriving it from a timestamp.
 */
@Component
public class ListingExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ListingExpirySweeper.class);

    private final ListingService listingService;

    public ListingExpirySweeper(ListingService listingService) {
        this.listingService = listingService;
    }

    /**
     * Every ten minutes. A listing may therefore stay visible a few minutes past its
     * expiry, which is the right trade: nobody is harmed, and a sweep this cheap does not
     * justify running every second.
     */
    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    public void expireDueListings() {
        int expired = listingService.expireDueListings();
        if (expired > 0) {
            log.info("Expired {} listing(s) past their expiry date", expired);
        }
    }
}
