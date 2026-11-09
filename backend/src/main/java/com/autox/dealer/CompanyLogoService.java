package com.autox.dealer;

import com.autox.api.error.ApiException;
import com.autox.listing.dto.ImageUploadRequest;
import com.autox.listing.dto.ImageUploadTicket;
import com.autox.listing.image.ImageMetadata;
import com.autox.listing.image.ImageProperties;
import com.autox.listing.image.ImageResizer;
import com.autox.storage.ObjectStorage;
import com.autox.storage.StorageProperties;
import com.autox.user.ProProfile;
import com.autox.user.ProProfileRepository;
import com.autox.user.UserRole;
import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A dealer's logo, through the listing photos' pipeline: the same handshake (a slot, a PUT
 * straight to storage, a confirmation), the same checks on what arrived, the same decoding.
 *
 * <p>What is kept is one JPEG, re-encoded from the decoded pixels, at most {@link #MAX_EDGE}
 * pixels on its longer side. Re-encoding is what removes the metadata: EXIF, GPS position,
 * camera serial number are in the uploaded file and in none of the bytes written back. The
 * orientation tag is acted on first, so a logo photographed on its side is not stored that way.
 * The upload itself is deleted once the logo is made.
 */
@Service
public class CompanyLogoService {

    private static final Logger log = LoggerFactory.getLogger(CompanyLogoService.class);

    /** Large enough for the dealer page's header at twice its size, small enough to load at once. */
    static final int MAX_EDGE = 400;

    /** A logo can be smaller than a photo of a car, but not an icon. */
    static final int MIN_EDGE = 64;

    private final ProProfileRepository profiles;
    private final ObjectStorage storage;
    private final StorageProperties storageProperties;
    private final ImageProperties imageProperties;
    private final ImageResizer resizer;

    CompanyLogoService(ProProfileRepository profiles, ObjectStorage storage, StorageProperties storageProperties,
                       ImageProperties imageProperties, ImageResizer resizer) {
        this.profiles = profiles;
        this.storage = storage;
        this.storageProperties = storageProperties;
        this.imageProperties = imageProperties;
        this.resizer = resizer;
    }

    @Transactional(readOnly = true)
    public ImageUploadTicket requestUpload(UUID userId, ImageUploadRequest request) {
        requireProfile(userId);
        String contentType = request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!imageProperties.isContentTypeAllowed(contentType)) {
            throw ApiException.unsupportedImageType(
                    "Only " + String.join(", ", imageProperties.allowedContentTypes()) + " can be uploaded.");
        }
        if (request.byteSize() > imageProperties.maxBytes()) {
            throw ApiException.imageTooLarge("A logo must be " + imageProperties.maxBytes() / (1024 * 1024)
                    + " MB or smaller.");
        }
        UUID uploadId = UUID.randomUUID();
        ObjectStorage.PresignedUpload presigned = storage.presignUpload(
                uploadKey(userId, uploadId), contentType, request.byteSize(), storageProperties.uploadUrlTtl());
        return new ImageUploadTicket(uploadId, presigned.url(), presigned.method(), presigned.requiredHeaders(),
                presigned.expiresAt(), presigned.maxBytes(), "/api/v1/me/company/logo/" + uploadId + "/confirm");
    }

    /** Makes the logo from what was uploaded, and replaces the previous one. */
    @Transactional
    public void confirm(UUID userId, UUID uploadId) {
        ProProfile profile = requireProfile(userId);
        String uploaded = uploadKey(userId, uploadId);
        ObjectStorage.StoredObject stored = storage.stat(uploaded)
                .orElseThrow(() -> ApiException.uploadNotFound(
                        "No uploaded file was found for this logo. Upload to the pre-signed URL first;"
                                + " if it has expired, request a new one."));
        if (stored.byteSize() > imageProperties.maxBytes()) {
            storage.delete(uploaded);
            throw ApiException.imageTooLarge("The uploaded file is larger than the limit.");
        }

        byte[] content = storage.read(uploaded);
        BufferedImage decoded;
        try {
            decoded = resizer.decode(content);
        } catch (ImageResizer.UnreadableImageException notAnImage) {
            storage.delete(uploaded);
            throw ApiException.unsupportedImageType(notAnImage.getMessage());
        }
        decoded = ImageMetadata.applyOrientation(decoded, ImageMetadata.readJpegOrientation(content));
        if (Math.min(decoded.getWidth(), decoded.getHeight()) < MIN_EDGE) {
            storage.delete(uploaded);
            throw ApiException.imageTooSmall("A logo must be at least " + MIN_EDGE
                    + " pixels on its shorter side.");
        }

        ImageResizer.Rendition logo = resizer.render(decoded, MAX_EDGE, imageProperties.jpegQuality());
        String key = "dealers/" + userId + "/logo-" + uploadId + ".jpg";
        storage.write(key, logo.content(), "image/jpeg");
        storage.delete(uploaded);

        String previous = profile.getLogoStorageKey();
        profile.setLogoStorageKey(key);
        deleteAfterCommit(previous);
        log.info("Logo {} set for dealer {} ({}x{})", uploadId, userId, logo.width(), logo.height());
    }

    @Transactional
    public void remove(UUID userId) {
        ProProfile profile = requireProfile(userId);
        String previous = profile.getLogoStorageKey();
        profile.setLogoStorageKey(null);
        deleteAfterCommit(previous);
    }

    private ProProfile requireProfile(UUID userId) {
        ProProfile profile = profiles.findByUserId(userId)
                .orElseThrow(() -> ApiException.forbidden("Only a PROFESSIONNEL account has a company logo."));
        if (profile.getUser().getRole() != UserRole.PROFESSIONNEL) {
            throw ApiException.forbidden("Only a PROFESSIONNEL account has a company logo.");
        }
        return profile;
    }

    /** Under the dealer's own prefix, so a confirmation can only ever read the caller's upload. */
    private static String uploadKey(UUID userId, UUID uploadId) {
        return "dealers/" + userId + "/uploads/" + uploadId;
    }

    /** The old logo goes once the new one is recorded, not before: a failed save keeps a working page. */
    private void deleteAfterCommit(String key) {
        if (key == null) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    storage.delete(key);
                } catch (RuntimeException e) {
                    log.warn("Previous logo {} could not be deleted: {}", key, e.getMessage());
                }
            }
        });
    }
}
