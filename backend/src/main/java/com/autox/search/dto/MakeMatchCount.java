package com.autox.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * A catalogue make and how many listings of it match a search: exactly what the search, with
 * that make added to the same filters, then lists.
 *
 * @param slug  the catalogue slug, which the search's {@code marque} takes
 * @param name  the make's name
 * @param count matching listings
 */
@Schema(description = "A make and how many listings of it match the filters.")
public record MakeMatchCount(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String slug,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long count) {
}
