package com.autox.savedsearch.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A saved search, as "Mes recherches" shows it.
 *
 * @param params       the results page's query string, without any make or model that has gone
 * @param newCount     listings new since it was last opened, at most 99
 * @param newCountCapped whether there are more than {@code newCount}
 * @param readable     false if its filters cannot be read: it runs no search and sends nothing
 * @param missingMake  the make that no longer exists, if one does not; the search runs without it
 * @param missingModel the same for the model
 */
public record SavedSearchView(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean paused,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> params,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int newCount,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean newCountCapped,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean readable,
        String missingMake,
        String missingModel,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
}
