package com.autox.listing.image;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Clears away upload slots that were requested and never confirmed.
 *
 * <p>Two kinds of waste accumulate without this. A row stuck at {@code PENDING} counts
 * against the listing's photo allowance, so a seller whose upload failed three times would
 * find themselves unable to add more. And if the client did upload but never confirmed, the
 * object sits in the bucket referenced by nothing — billed monthly, forever.
 *
 * <p>Only slots whose pre-signed URL has already expired are touched, so an upload still in
 * flight is never pulled out from under a client.
 */
@Component
public class ListingImageSweeper {

    private static final Logger log = LoggerFactory.getLogger(ListingImageSweeper.class);

    private final ListingImageService imageService;

    public ListingImageSweeper(ListingImageService imageService) {
        this.imageService = imageService;
    }

    @Scheduled(fixedDelayString = "PT15M", initialDelayString = "PT2M")
    public void purgeAbandonedUploads() {
        try {
            int removed = imageService.purgeAbandonedUploads();
            if (removed > 0) {
                log.info("Removed {} abandoned upload slot(s)", removed);
            }
        } catch (RuntimeException e) {
            // A storage outage must not kill the scheduler thread; the next run retries.
            log.error("Abandoned-upload sweep failed: {}", e.getMessage());
        }
    }
}
