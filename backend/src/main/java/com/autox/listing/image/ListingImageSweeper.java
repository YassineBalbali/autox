package com.autox.listing.image;

import com.autox.scheduling.ScheduledTaskCoordinator;
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
 *
 * <p>Dealers' logo uploads are swept here too ({@code CompanyLogoService}): the same handshake,
 * the same waste when it stops halfway.
 */
@Component
public class ListingImageSweeper {

    private static final Logger log = LoggerFactory.getLogger(ListingImageSweeper.class);

    private final ListingImageService imageService;
    private final com.autox.dealer.CompanyLogoService logos;
    private final ScheduledTaskCoordinator coordinator;

    public ListingImageSweeper(ListingImageService imageService, com.autox.dealer.CompanyLogoService logos,
                               ScheduledTaskCoordinator coordinator) {
        this.imageService = imageService;
        this.logos = logos;
        this.coordinator = coordinator;
    }

    /**
     * One instance at a time. This sweep deletes objects from storage as well as rows, so
     * two instances running it together would race on the same abandoned upload — the one
     * place among the scheduled jobs where duplicated work is not merely wasteful. Every
     * fifteen minutes by default; configurable (autox.scheduling).
     */
    @Scheduled(fixedDelayString = "${autox.scheduling.image-cleanup-interval}", initialDelayString = "PT2M")
    public void purgeAbandonedUploads() {
        coordinator.runExclusively("listing-image-cleanup", () -> {
            try {
                int removed = imageService.purgeAbandonedUploads();
                if (removed > 0) {
                    log.info("Removed {} abandoned upload slot(s)", removed);
                }
                int logos = this.logos.purgeAbandonedUploads();
                if (logos > 0) {
                    log.info("Removed {} abandoned logo upload slot(s)", logos);
                }
            } catch (RuntimeException e) {
                // A storage outage must not kill the scheduler thread; the next run retries.
                log.error("Abandoned-upload sweep failed: {}", e.getMessage());
            }
        });
    }
}
