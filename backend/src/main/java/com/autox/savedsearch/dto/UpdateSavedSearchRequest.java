package com.autox.savedsearch.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Only what is present changes.
 *
 * @param name   a new name
 * @param paused true to pause its alerts, false to resume them
 */
public record UpdateSavedSearchRequest(
        @Size(min = 1, max = 80) @Pattern(regexp = ".*\\S.*", message = "must not be blank") String name,
        Boolean paused) {
}
