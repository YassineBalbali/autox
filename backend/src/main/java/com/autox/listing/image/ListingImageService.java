package com.autox.listing.image;

import com.autox.api.error.ApiException;
import com.autox.listing.ImageStatus;
import com.autox.listing.ImageVariantType;
import com.autox.listing.Listing;
import com.autox.listing.ListingAccessGuard;
import com.autox.listing.ListingImage;
import com.autox.listing.ListingImageRepository;
import com.autox.listing.ListingImageVariant;
import com.autox.listing.ListingRepository;
import com.autox.listing.dto.ImageResponse;
import com.autox.listing.dto.ImageUploadRequest;
import com.autox.listing.dto.ImageUploadTicket;
import com.autox.storage.ObjectStorage;
import com.autox.storage.StorageProperties;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Photo upload, confirmation, ordering and removal.
 *
 * <p>The upload is a three-step handshake, and the bytes never pass through the API:
 * <ol>
 *   <li>The client asks for a slot. A {@code PENDING} row is created and a pre-signed URL
 *       returned, with the content type and length bound into the signature.</li>
 *   <li>The client PUTs the file straight to storage.</li>
 *   <li>The client confirms. Only now does the server look at the object: it checks what
 *       actually arrived, decodes it — a declared content type proves nothing — derives the
 *       variants, and marks the row {@code READY}.</li>
 * </ol>
 *
 * <p>A row that never reaches step three is an orphan, which is why the slot carries an
 * expiry and {@link ListingImageSweeper} exists. An upload refused at step three is deleted
 * there and then, with every file stored for it, and the rows after it move up: a listing
 * holds only its photos and the uploads under way, at consecutive positions from zero.
 *
 * <p><b>One change at a time per listing.</b> Every method that writes a listing's photos
 * first locks the listing's row ({@link ListingRepository#lockPhotos}) and reads the photos
 * only after, so two of them never renumber the same rows from different snapshots: the
 * sweeper waits for a seller's reorder, a reorder for an upload being confirmed. The site
 * sends a seller's photos one at a time, so in practice nothing waits.
 */
@Service
public class ListingImageService {

    private static final Logger log = LoggerFactory.getLogger(ListingImageService.class);

    /**
     * Higher than the variant quality: this copy is the archive every future rendition would
     * be derived from, so it should not accumulate compression artefacts.
     */
    private static final float ORIGINAL_REENCODE_QUALITY = 0.95f;

    private final ListingAccessGuard accessGuard;
    private final ListingImageRepository images;
    private final ListingRepository listings;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final ImageProperties imageProperties;
    private final ImageResizer resizer;
    private final TransactionTemplate transactions;

    public ListingImageService(ListingAccessGuard accessGuard,
                               ListingImageRepository images,
                               ListingRepository listings,
                               ObjectStorage storage,
                               StorageProperties storageProperties,
                               ImageProperties imageProperties,
                               ImageResizer resizer,
                               PlatformTransactionManager transactionManager) {
        this.accessGuard = accessGuard;
        this.images = images;
        this.listings = listings;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.imageProperties = imageProperties;
        this.resizer = resizer;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // --- step 1: ask for a slot -------------------------------------------

    @Transactional
    public ImageUploadTicket requestUpload(UUID callerId, boolean admin, UUID listingId,
                                           ImageUploadRequest request) {
        // Before the count and the next position are read: two slots asked for at once would
        // otherwise take the same position, and the second would fail at commit.
        listings.lockPhotos(listingId);
        Listing listing = accessGuard.require(callerId, admin, listingId);
        requireEditable(listing);

        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!imageProperties.isContentTypeAllowed(contentType)) {
            throw ApiException.unsupportedImageType(
                    "Only " + String.join(", ", imageProperties.allowedContentTypes()) + " can be uploaded.");
        }
        if (request.byteSize() > imageProperties.maxBytes()) {
            throw ApiException.imageTooLarge("Images must be " + megabytes(imageProperties.maxBytes())
                    + " MB or smaller; this one is " + megabytes(request.byteSize()) + " MB.");
        }

        long used = images.countUsableByListing(listingId);
        if (used >= imageProperties.maxPerListing()) {
            throw ApiException.tooManyImages("A listing may have at most "
                    + imageProperties.maxPerListing() + " photos; this one already has " + used + ".");
        }

        short position = (short) (images.findMaxPosition(listingId) + 1);
        Instant expiresAt = Instant.now().plus(storageProperties.uploadUrlTtl());

        ListingImage image = ListingImage.pending(listing, position, "placeholder",
                sanitizeFilename(request.filename()), contentType, request.byteSize(), expiresAt);
        // The key embeds the image id, which is why the row is created first.
        image.setStorageKey(originalKey(listingId, image.getId(), contentType));
        images.save(image);

        ObjectStorage.PresignedUpload presigned = storage.presignUpload(
                image.getStorageKey(), contentType, request.byteSize(), storageProperties.uploadUrlTtl());

        log.debug("Issued upload slot {} for listing {}", image.getId(), listingId);
        return new ImageUploadTicket(
                image.getId(),
                presigned.url(),
                presigned.method(),
                presigned.requiredHeaders(),
                presigned.expiresAt(),
                presigned.maxBytes(),
                "/api/v1/listings/" + listingId + "/images/" + image.getId() + "/confirm");
    }

    // --- step 3: confirm --------------------------------------------------

    /**
     * Verifies the uploaded object and builds the variants.
     *
     * <p>Idempotent: confirming an already-ready image returns it unchanged, because a mobile
     * client that loses its connection mid-response will retry.
     *
     * <p>An upload refused here — too large, not an image, too small, or anything else going
     * wrong with its file — is <b>removed</b>: its row is deleted and the rows after it move
     * up, in a transaction that commits, and then every file stored for it is deleted. The
     * seller is told why and can choose another photo; nothing is left to hold a position, the
     * photo allowance or storage. Hence a transaction template rather than
     * {@code @Transactional}: the removal must commit although the request fails.
     */
    public ImageResponse confirmUpload(UUID callerId, boolean admin, UUID listingId, UUID imageId) {
        Confirmation outcome = transactions.execute(status -> confirmLocked(callerId, admin, listingId, imageId));
        if (outcome.refusal() == null) {
            return outcome.image();
        }
        // Committed: the row is gone. Its files go now; storage last, as in delete().
        deleteObjects(imageId, outcome.removedKeys());
        throw outcome.refusal();
    }

    /** What a confirmation came to: the photo, or why it was refused and what to delete. */
    private record Confirmation(ImageResponse image, RuntimeException refusal, List<String> removedKeys) {
    }

    private Confirmation confirmLocked(UUID callerId, boolean admin, UUID listingId, UUID imageId) {
        listings.lockPhotos(listingId);
        Listing listing = accessGuard.require(callerId, admin, listingId);

        ListingImage image = images.findByIdAndListing(imageId, listingId)
                .orElseThrow(() -> ApiException.notFound("Image"));

        if (image.getStatus() == ImageStatus.READY) {
            return new Confirmation(ImageResponse.from(image, storage::readUrl), null, List.of());
        }

        Optional<ObjectStorage.StoredObject> stored;
        Processed processed = null;
        try {
            stored = storage.stat(image.getStorageKey());
            if (stored.isPresent()) {
                processed = process(image, stored.get());
            }
        } catch (RuntimeException refused) {
            return remove(image, refused);
        }
        if (processed == null) {
            // Not a refusal: the client may confirm before its last byte has landed, and can
            // try again. The slot stays until it is confirmed or its URL expires.
            throw ApiException.uploadNotFound(
                    "No uploaded file was found for this image. Upload to the pre-signed URL first;"
                            + " if it has expired, request a new one.");
        }

        image.getVariants().clear();
        for (Processed.Variant variant : processed.variants()) {
            image.getVariants().add(ListingImageVariant.of(image, variant.type(), variant.key(),
                    variant.width(), variant.height(), variant.byteSize()));
        }
        image.setWidth(processed.width());
        image.setHeight(processed.height());
        // The size and checksum of what is now stored, not of what was uploaded.
        image.setByteSize((long) processed.content().length);
        image.setChecksumSha256(com.autox.common.Sha256.hex(processed.content()));
        image.setUploadedAt(Instant.now());
        image.setStatus(ImageStatus.READY);

        // A photo nobody has seen is now on the listing: an approval given before it
        // arrived does not cover it. See Listing#contentChanged.
        if (listing.contentChanged()) {
            log.info("Listing {} gained a photo after approval and is back in the moderation queue", listingId);
        }

        log.info("Image {} confirmed for listing {} ({}x{}, {} variants)",
                imageId, listingId, processed.width(), processed.height(), image.getVariants().size());
        return new Confirmation(ImageResponse.from(image, storage::readUrl), null, List.of());
    }

    /** The checked, sanitised upload and its renditions, already written to storage. */
    private record Processed(int width, int height, byte[] content, List<Variant> variants) {
        record Variant(ImageVariantType type, String key, int width, int height, int byteSize) {
        }
    }

    /**
     * Checks the stored object and writes its renditions. Touches storage only, never the row,
     * so that a refusal leaves the row as it was for {@link #remove} to delete.
     */
    private Processed process(ListingImage image, ObjectStorage.StoredObject stored) {
        if (stored.byteSize() > imageProperties.maxBytes()) {
            throw ApiException.imageTooLarge("The uploaded file is larger than the "
                    + megabytes(imageProperties.maxBytes()) + " MB limit.");
        }

        byte[] content = storage.read(image.getStorageKey());

        BufferedImage decoded;
        try {
            // The only trustworthy check: a content type header is whatever the client typed.
            decoded = resizer.decode(content);
        } catch (ImageResizer.UnreadableImageException notAnImage) {
            throw ApiException.unsupportedImageType(notAnImage.getMessage());
        }

        // Orientation is read before anything is discarded: the tag has to be acted on, or
        // stripping it would leave every portrait photo lying on its side.
        int orientation = ImageMetadata.readJpegOrientation(content);
        decoded = ImageMetadata.applyOrientation(decoded, orientation);

        int shorterEdge = Math.min(decoded.getWidth(), decoded.getHeight());
        if (shorterEdge < imageProperties.minDimension()) {
            throw ApiException.imageTooSmall("Images must be at least "
                    + imageProperties.minDimension() + " pixels on the shorter edge; this one is "
                    + shorterEdge + ".");
        }

        content = rewriteStoredOriginal(image, content, decoded, orientation, stored.contentType());

        UUID listingId = image.getListing().getId();
        List<Processed.Variant> variants = new ArrayList<>();
        for (Map.Entry<ImageVariantType, Integer> entry : imageProperties.variantMaxEdge().entrySet()) {
            ImageVariantType type = entry.getKey();
            ImageResizer.Rendition rendition = resizer.render(decoded, entry.getValue(),
                    imageProperties.jpegQuality());

            String key = variantKey(listingId, image.getId(), type);
            storage.write(key, rendition.content(), "image/jpeg");
            variants.add(new Processed.Variant(type, key,
                    rendition.width(), rendition.height(), rendition.content().length));
        }
        return new Processed(decoded.getWidth(), decoded.getHeight(), content, variants);
    }

    /**
     * Deletes a refused upload's row and closes the gap, inside the confirmation's transaction,
     * which then commits; the caller deletes the files once it has.
     */
    private Confirmation remove(ListingImage image, RuntimeException refusal) {
        UUID listingId = image.getListing().getId();
        List<String> keys = everyKeyOf(image);
        images.delete(image);
        images.flush();
        renumber(listingId);
        log.info("Upload {} for listing {} refused and removed: {}", image.getId(), listingId, refusal.getMessage());
        return new Confirmation(null, refusal, keys);
    }

    // --- read -------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<ImageResponse> list(UUID callerId, boolean admin, UUID listingId) {
        accessGuard.require(callerId, admin, listingId);
        return images.findByListing(listingId).stream()
                .map(image -> ImageResponse.from(image, storage::readUrl))
                .toList();
    }

    // --- reorder ----------------------------------------------------------

    /**
     * Applies a new gallery order.
     *
     * <p>The whole set of ready photos must be supplied. Positions are then rewritten in
     * place, which transiently duplicates values — allowed because the unique constraint on
     * {@code (listing_id, position)} is deferred to commit. Without that, this would need a
     * two-pass shuffle through negative positions.
     */
    @Transactional
    public List<ImageResponse> reorder(UUID callerId, boolean admin, UUID listingId, List<UUID> order) {
        listings.lockPhotos(listingId);
        accessGuard.require(callerId, admin, listingId);

        List<ListingImage> all = images.findByListing(listingId);
        List<ListingImage> ready = all.stream().filter(image -> image.getStatus() == ImageStatus.READY).toList();
        if (ready.isEmpty()) {
            throw ApiException.conflict("This listing has no confirmed photos to reorder.");
        }

        Set<UUID> requested = new LinkedHashSet<>(order);
        if (requested.size() != order.size()) {
            throw ApiException.validationFailed("imageIds contains duplicates.");
        }
        Set<UUID> actual = new HashSet<>();
        ready.forEach(image -> actual.add(image.getId()));
        if (!requested.equals(actual)) {
            throw ApiException.validationFailed(
                    "imageIds must list every confirmed photo of this listing exactly once ("
                            + actual.size() + " expected).");
        }

        Map<UUID, ListingImage> byId = new java.util.HashMap<>();
        ready.forEach(image -> byId.put(image.getId(), image));

        short position = 0;
        for (UUID id : order) {
            byId.get(id).setPosition(position++);
        }
        // Then every other row — an upload under way, or a FAILED row an earlier version left —
        // after the photos, in the order it had. Every row gets a new position, so none keeps
        // one a photo has just taken; numbering the photos alone did exactly that, and the
        // unique constraint refused the commit.
        for (ListingImage image : all) {
            if (image.getStatus() != ImageStatus.READY) {
                image.setPosition(position++);
            }
        }
        images.flush();

        return images.findReadyByListing(listingId).stream()
                .map(image -> ImageResponse.from(image, storage::readUrl))
                .toList();
    }

    // --- delete -----------------------------------------------------------

    /**
     * Removes a photo and everything derived from it, then closes the gap in the ordering so
     * positions stay contiguous from zero.
     */
    @Transactional
    public void delete(UUID callerId, boolean admin, UUID listingId, UUID imageId) {
        listings.lockPhotos(listingId);
        Listing listing = accessGuard.require(callerId, admin, listingId);

        ListingImage image = images.findByIdAndListing(imageId, listingId)
                .orElseThrow(() -> ApiException.notFound("Image"));

        // Removing a photo that was part of what was approved changes what the listing
        // shows — the one honest picture of the damage, say. Reordering does not: the same
        // photos, all already seen. An upload that never completed was never a photo.
        if (image.getStatus() == ImageStatus.READY && listing.contentChanged()) {
            log.info("Listing {} lost a photo after approval and is back in the moderation queue", listingId);
        }

        List<String> keys = everyKeyOf(image);
        images.delete(image);
        images.flush();

        renumber(listingId);

        // Storage last: a failure here leaves bytes nobody references, which the sweeper can
        // clean up later. Deleting storage first and then failing would leave a row pointing
        // at nothing, which the API would serve as a broken image.
        deleteObjects(imageId, keys);
    }

    // --- clean-up ---------------------------------------------------------

    /**
     * Removes every row that will never be a photo, with its files: upload slots whose URL
     * expired before they were confirmed, and the {@code FAILED} rows earlier versions kept
     * for refused uploads. The listing's other rows then take consecutive positions again, in
     * the order they had. For {@link ListingImageSweeper}; returns how many rows went.
     *
     * <p>One transaction per listing, holding the listing's photo lock
     * ({@link ListingRepository#lockPhotos}): it waits for a seller's change in progress, and
     * the seller's next change waits for it. The rows are looked up again once the lock is
     * held, so a slot confirmed in the meantime is a photo and is left alone, and a second run
     * finds nothing to do. Files are deleted after the commit, as in {@link #delete}.
     */
    public int purgeDeadUploads() {
        Instant now = Instant.now();
        int removed = 0;
        for (UUID listingId : images.findListingsWithDeadUploads(now)) {
            try {
                removed += purgeDeadUploads(listingId, now);
            } catch (RuntimeException e) {
                // One listing's trouble does not hold up the others; the next run retries it.
                log.warn("Could not clean up the dead uploads of listing {}: {}", listingId, e.getMessage());
            }
        }
        return removed;
    }

    private int purgeDeadUploads(UUID listingId, Instant now) {
        List<String> keys = new ArrayList<>();
        Integer removed = transactions.execute(status -> {
            if (listings.lockPhotos(listingId).isEmpty()) {
                return 0;
            }
            List<ListingImage> dead = images.findDeadUploads(listingId, now);
            if (dead.isEmpty()) {
                return 0;
            }
            dead.forEach(image -> keys.addAll(everyKeyOf(image)));
            images.deleteAll(dead);
            images.flush();
            renumber(listingId);
            return dead.size();
        });
        // Covers the slot uploaded but never confirmed, and the file of an upload refused as
        // too large, which earlier versions left in the bucket: referenced by nothing, and
        // otherwise paid for forever.
        deleteObjects(listingId, keys);
        return removed == null ? 0 : removed;
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Replaces the stored original with a copy carrying no metadata, and returns the bytes
     * that are now in storage.
     *
     * <p>Two paths, and the difference matters:
     * <ul>
     *   <li><b>Upright already</b> — the metadata segments are removed without touching the
     *       compressed image data. Byte-identical pixels, no generational loss.</li>
     *   <li><b>Rotated</b> — the orientation tag is being discarded, so the rotation has to be
     *       baked into the pixels, which means re-encoding. Done at a high quality setting,
     *       since this copy is the one any future re-processing starts from.</li>
     * </ul>
     *
     * <p>A failure here is logged and swallowed: the gallery is already correct, and keeping an
     * original with EXIF is better than failing an upload the seller has already completed.
     * The condition is reported so it can be found again.
     */
    private byte[] rewriteStoredOriginal(ListingImage image, byte[] uploaded, BufferedImage upright,
                                         int orientation, String contentType) {
        try {
            byte[] sanitised;
            String storedType;

            if (orientation != ImageMetadata.ORIENTATION_NORMAL) {
                sanitised = resizer.encodeJpeg(upright, ORIGINAL_REENCODE_QUALITY);
                storedType = "image/jpeg";
            } else {
                sanitised = ImageMetadata.strip(uploaded);
                storedType = contentType == null ? image.getContentType() : contentType;
                if (sanitised.length == uploaded.length) {
                    // Nothing to remove; leave the object exactly as uploaded.
                    return uploaded;
                }
            }

            storage.write(image.getStorageKey(), sanitised, storedType);
            log.debug("Stored original for image {} sanitised: {} -> {} bytes (orientation {})",
                    image.getId(), uploaded.length, sanitised.length, orientation);
            return sanitised;
        } catch (RuntimeException e) {
            log.warn("Could not strip metadata from the original of image {}; it is stored as uploaded: {}",
                    image.getId(), e.getMessage());
            return uploaded;
        }
    }

    /** Consecutive positions from zero, in the current order. Under the listing's photo lock. */
    private void renumber(UUID listingId) {
        List<ListingImage> remaining = images.findByListing(listingId);
        short position = 0;
        for (ListingImage image : remaining) {
            image.setPosition(position++);
        }
        images.flush();
    }

    /**
     * Every key a file of this image can be stored under: the original and each rendition,
     * whether or not the row has recorded it yet — a refusal can come after some renditions
     * were written. Deleting a key with nothing under it does nothing.
     */
    private static List<String> everyKeyOf(ListingImage image) {
        Set<String> keys = new LinkedHashSet<>(image.allStorageKeys());
        for (ImageVariantType type : ImageVariantType.values()) {
            keys.add(variantKey(image.getListing().getId(), image.getId(), type));
        }
        return List.copyOf(keys);
    }

    /** After the rows are gone: a failure leaves files nobody references, and is logged. */
    private void deleteObjects(UUID owner, List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        try {
            storage.deleteAll(keys);
        } catch (RuntimeException e) {
            log.warn("Rows of {} removed but {} object(s) may remain in storage: {}",
                    owner, keys.size(), e.getMessage());
        }
    }

    private static void requireEditable(Listing listing) {
        if (!listing.getStatus().isEditable()) {
            throw ApiException.conflict("Photos cannot be changed on a listing in state "
                    + listing.getStatus() + ".");
        }
    }

    /**
     * {@code listings/{listingId}/{imageId}/original.jpg}. Keys are derived, never taken from
     * the client: a filename that traverses directories or collides with another seller's
     * object is then impossible by construction.
     */
    private static String originalKey(UUID listingId, UUID imageId, String contentType) {
        return "listings/" + listingId + "/" + imageId + "/original" + extensionFor(contentType);
    }

    private static String variantKey(UUID listingId, UUID imageId, ImageVariantType variant) {
        return "listings/" + listingId + "/" + imageId + "/" + variant.name().toLowerCase(Locale.ROOT) + ".jpg";
    }

    private static String extensionFor(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            case "image/avif" -> ".avif";
            default -> ".jpg";
        };
    }

    /** Kept only for display; it never influences the storage key. */
    private static String sanitizeFilename(String filename) {
        String trimmed = filename.trim().replaceAll("[\\p{Cntrl}/\\\\]", "_");
        return trimmed.length() <= 255 ? trimmed : trimmed.substring(trimmed.length() - 255);
    }

    private static String megabytes(long bytes) {
        return new java.text.DecimalFormat("#.#", new java.text.DecimalFormatSymbols(Locale.ENGLISH))
                .format(bytes / (1024.0 * 1024.0));
    }
}
