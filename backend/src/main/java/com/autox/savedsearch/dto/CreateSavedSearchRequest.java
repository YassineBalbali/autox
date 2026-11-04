package com.autox.savedsearch.dto;

import com.autox.search.ListingSearchCriteria;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * Saves a search.
 *
 * @param name     what the user calls it
 * @param criteria what {@code GET /api/v1/listings} is sent for this search
 * @param params   the results page's query string for the same search, for the links back to it
 */
public record CreateSavedSearchRequest(
        @NotBlank @Size(max = 80) String name,
        @NotNull @Valid ListingSearchCriteria criteria,
        Map<String, String> params) {
}
