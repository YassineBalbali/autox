package com.autox.listing.image;

import com.autox.listing.ImageVariantType;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Image upload policy, bound from {@code autox.images.*}.
 *
 * <p>The accepted content types are narrower than the database allows on purpose: variant
 * generation uses the JDK's own image decoders, which read JPEG and PNG everywhere but need
 * a third-party plugin for WebP and AVIF. Accepting a format we cannot resize would let a
 * seller upload a file that then fails to produce a gallery.
 */
@ConfigurationProperties(prefix = "autox.images")
public record ImageProperties(

        int maxPerListing,
        long maxBytes,
        List<String> allowedContentTypes,

        /** Smallest acceptable original, in pixels. Rejects icons and tracking pixels. */
        int minDimension,

        /** Longest edge of each variant. */
        Map<ImageVariantType, Integer> variantMaxEdge,

        /** JPEG quality for generated variants, 0–1. */
        float jpegQuality) {

    public ImageProperties {
        if (maxPerListing <= 0) {
            maxPerListing = 20;
        }
        if (maxBytes <= 0) {
            maxBytes = 10L * 1024 * 1024;
        }
        if (allowedContentTypes == null || allowedContentTypes.isEmpty()) {
            allowedContentTypes = List.of("image/jpeg", "image/png");
        }
        if (minDimension <= 0) {
            minDimension = 400;
        }
        if (variantMaxEdge == null || variantMaxEdge.isEmpty()) {
            variantMaxEdge = Map.of(
                    ImageVariantType.THUMBNAIL, 320,
                    ImageVariantType.CARD, 800,
                    ImageVariantType.FULL, 1600);
        }
        if (jpegQuality <= 0 || jpegQuality > 1) {
            jpegQuality = 0.82f;
        }
    }

    public boolean isContentTypeAllowed(String contentType) {
        return contentType != null && allowedContentTypes.contains(contentType.toLowerCase(java.util.Locale.ROOT));
    }
}
