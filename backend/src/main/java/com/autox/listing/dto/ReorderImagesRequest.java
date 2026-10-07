package com.autox.listing.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * The gallery's new order, front to back. Position 0 is the cover photo.
 *
 * <p>Must be a complete permutation of the listing's ready images — a partial list is
 * rejected rather than interpreted, because "move this one to the front" and "these are the
 * only ones I know about" are indistinguishable otherwise, and guessing wrong silently
 * reorders someone's gallery.
 */
public record ReorderImagesRequest(

        @NotEmpty List<UUID> imageIds) {
}
