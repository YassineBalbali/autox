package com.autox.listing.image;

import com.autox.listing.ImageStatus;
import com.autox.listing.ListingImageRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Marks an upload slot as failed, in a transaction that commits even though the caller is
 * about to throw.
 *
 * <p>The same ordering trap as refresh-token replay detection and login throttling: rejecting
 * an upload means recording the rejection <em>and</em> failing the request. Doing both in one
 * transaction rolls the record back, leaving the slot {@code PENDING} — so it would keep
 * occupying the listing's photo allowance and the sweeper would be the only thing that ever
 * cleared it.
 *
 * <p>A separate bean because Spring's transaction proxy does not intercept self-invocation.
 */
@Component
public class ImageFailureRecorder {

    private final ListingImageRepository images;

    public ImageFailureRecorder(ListingImageRepository images) {
        this.images = images;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID imageId) {
        images.findById(imageId).ifPresent(image -> image.setStatus(ImageStatus.FAILED));
    }
}
