package com.autox.listing.dto;

import com.autox.listing.ImageStatus;
import com.autox.listing.ImageVariantType;
import com.autox.listing.ListingImage;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * One photo as the API exposes it: an id, its place in the gallery, and a URL per variant.
 *
 * <p>Storage keys are not exposed. A client that knew them could construct URLs we did not
 * issue, and changing bucket layout would then be a breaking API change.
 */
public record ImageResponse(
        UUID id,
        int position,
        ImageStatus status,
        Integer width,
        Integer height,
        Long byteSize,
        Map<ImageVariantType, VariantView> variants) {

    public static ImageResponse from(ListingImage image, Function<String, String> urlResolver) {
        Map<ImageVariantType, VariantView> variants = new EnumMap<>(ImageVariantType.class);
        image.getVariants().forEach(variant -> variants.put(variant.getVariant(),
                new VariantView(urlResolver.apply(variant.getStorageKey()),
                        variant.getWidth(), variant.getHeight(), variant.getByteSize())));

        return new ImageResponse(
                image.getId(),
                image.getPosition(),
                image.getStatus(),
                image.getWidth(),
                image.getHeight(),
                image.getByteSize(),
                variants);
    }

    /** Dimensions travel with the URL so a client can reserve layout space before loading. */
    public record VariantView(String url, int width, int height, Long byteSize) {
    }
}
