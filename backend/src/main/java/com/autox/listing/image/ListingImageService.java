package com.autox.listing.image;

import com.autox.api.error.ApiException;
import com.autox.listing.ImageStatus;
import com.autox.listing.ImageVariantType;
import com.autox.listing.Listing;
import com.autox.listing.ListingAccessGuard;
import com.autox.listing.ListingImage;
import com.autox.listing.ListingImageRepository;
import com.autox.listing.ListingImageVariant;
import com.autox.listing.dto.ImageResponse;
import com.autox.listing.dto.ImageUploadRequest;
import com.autox.listing.dto.ImageUploadTicket;
import com.autox.storage.ObjectStorage;
import com.autox.storage.StorageProperties;
import java.awt.image.BufferedImage;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * expiry and {@link ListingImageSweeper} exists.
 */
@Service
public class ListingImageService {

    private static final Logger log = LoggerFactory.getLogger(ListingImageService.class);

    private final ListingAccessGuard accessGuard;
    private final ListingImageRepository images;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final ImageProperties imageProperties;
    private final ImageResizer resizer;
    private final ImageFailureRecorder failureRecorder;

    public ListingImageService(ListingAccessGuard accessGuard,
                               ListingImageRepository images,
                               ObjectStorage storage,
                               StorageProperties storageProperties,
                               ImageProperties imageProperties,
                               ImageResizer resizer,
                               ImageFailureRecorder failureRecorder) {
        this.accessGuard = accessGuard;
        this.images = images;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.imageProperties = imageProperties;
        this.resizer = resizer;
        this.failureRecorder = failureRecorder;
    }

    // --- step 1: ask for a slot -------------------------------------------

    @Transactional
    public ImageUploadTicket requestUpload(UUID callerId, boolean admin, UUID listingId,
                                           ImageUploadRequest request) {
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
     */
    @Transactional
    public ImageResponse confirmUpload(UUID callerId, boolean admin, UUID listingId, UUID imageId) {
        accessGuard.require(callerId, admin, listingId);

        ListingImage image = images.findByIdAndListing(imageId, listingId)
                .orElseThrow(() -> ApiException.notFound("Image"));

        if (image.getStatus() == ImageStatus.READY) {
            return ImageResponse.from(image, storage::readUrl);
        }

        ObjectStorage.StoredObject stored = storage.stat(image.getStorageKey())
                .orElseThrow(() -> ApiException.uploadNotFound(
                        "No uploaded file was found for this image. Upload to the pre-signed URL first;"
                                + " if it has expired, request a new one."));

        if (stored.byteSize() > imageProperties.maxBytes()) {
            fail(image);
            throw ApiException.imageTooLarge("The uploaded file is larger than the "
                    + megabytes(imageProperties.maxBytes()) + " MB limit.");
        }

        byte[] content = storage.read(image.getStorageKey());

        BufferedImage decoded;
        try {
            // The only trustworthy check: a content type header is whatever the client typed.
            decoded = resizer.decode(content);
        } catch (ImageResizer.UnreadableImageException notAnImage) {
            fail(image);
            storage.delete(image.getStorageKey());
            throw ApiException.unsupportedImageType(notAnImage.getMessage());
        }

        if (Math.min(decoded.getWidth(), decoded.getHeight()) < imageProperties.minDimension()) {
            fail(image);
            storage.delete(image.getStorageKey());
            throw ApiException.imageTooSmall("Images must be at least "
                    + imageProperties.minDimension() + " pixels on the shorter edge; this one is "
                    + Math.min(decoded.getWidth(), decoded.getHeight()) + ".");
        }

        image.getVariants().clear();
        for (Map.Entry<ImageVariantType, Integer> entry : imageProperties.variantMaxEdge().entrySet()) {
            ImageVariantType type = entry.getKey();
            ImageResizer.Rendition rendition = resizer.render(decoded, entry.getValue(),
                    imageProperties.jpegQuality());

            String key = variantKey(listingId, imageId, type);
            storage.write(key, rendition.content(), "image/jpeg");
            image.getVariants().add(ListingImageVariant.of(image, type, key,
                    rendition.width(), rendition.height(), rendition.content().length));
        }

        image.setWidth(decoded.getWidth());
        image.setHeight(decoded.getHeight());
        image.setByteSize(stored.byteSize());
        image.setChecksumSha256(sha256(content));
        image.setUploadedAt(Instant.now());
        image.setStatus(ImageStatus.READY);

        log.info("Image {} confirmed for listing {} ({}x{}, {} variants)",
                imageId, listingId, decoded.getWidth(), decoded.getHeight(), image.getVariants().size());
        return ImageResponse.from(image, storage::readUrl);
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
     * <p>The whole set must be supplied. Positions are then rewritten in place, which
     * transiently duplicates values — allowed because the unique constraint on
     * {@code (listing_id, position)} is deferred to commit. Without that, this would need a
     * two-pass shuffle through negative positions.
     */
    @Transactional
    public List<ImageResponse> reorder(UUID callerId, boolean admin, UUID listingId, List<UUID> order) {
        accessGuard.require(callerId, admin, listingId);

        List<ListingImage> ready = images.findReadyByListing(listingId);
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
        accessGuard.require(callerId, admin, listingId);

        ListingImage image = images.findByIdAndListing(imageId, listingId)
                .orElseThrow(() -> ApiException.notFound("Image"));

        List<String> keys = image.allStorageKeys();
        images.delete(image);
        images.flush();

        renumber(listingId);

        // Storage last: a failure here leaves bytes nobody references, which the sweeper can
        // clean up later. Deleting storage first and then failing would leave a row pointing
        // at nothing, which the API would serve as a broken image.
        try {
            storage.deleteAll(keys);
        } catch (RuntimeException e) {
            log.warn("Image {} removed from the database but {} object(s) remain in storage: {}",
                    imageId, keys.size(), e.getMessage());
        }
    }

    /** Used by the sweeper; expects to be called inside a transaction. */
    @Transactional
    public int purgeAbandonedUploads() {
        List<ListingImage> abandoned = images.findAbandonedUploads(Instant.now());
        if (abandoned.isEmpty()) {
            return 0;
        }

        List<String> keys = new ArrayList<>();
        Set<UUID> listingIds = new LinkedHashSet<>();
        for (ListingImage image : abandoned) {
            keys.addAll(image.allStorageKeys());
            listingIds.add(image.getListing().getId());
        }

        images.deleteAll(abandoned);
        images.flush();
        listingIds.forEach(this::renumber);

        try {
            // Covers the case where the client did upload but never confirmed: the object is
            // there, unreferenced, and would otherwise be paid for forever.
            storage.deleteAll(keys);
        } catch (RuntimeException e) {
            log.warn("Could not delete {} orphaned object(s): {}", keys.size(), e.getMessage());
        }
        return abandoned.size();
    }

    // --- helpers ----------------------------------------------------------

    private void renumber(UUID listingId) {
        List<ListingImage> remaining = images.findByListing(listingId);
        short position = 0;
        for (ListingImage image : remaining) {
            image.setPosition(position++);
        }
        images.flush();
    }

    /**
     * Records the failure in its own transaction, because every caller throws immediately
     * afterwards and the caller's transaction will be rolled back.
     */
    private void fail(ListingImage image) {
        failureRecorder.markFailed(image.getId());
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

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }

    private static String megabytes(long bytes) {
        return new java.text.DecimalFormat("#.#", new java.text.DecimalFormatSymbols(Locale.ENGLISH))
                .format(bytes / (1024.0 * 1024.0));
    }
}
