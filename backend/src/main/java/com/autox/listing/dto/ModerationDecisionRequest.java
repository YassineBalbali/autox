package com.autox.listing.dto;

import com.autox.listing.ModerationReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Why a listing is being refused or taken down.
 *
 * <p>The reason is required: a listing that disappears without one cannot be explained to
 * its seller or defended in a dispute. The note is the moderator's own words, kept for
 * other moderators, and required only when the reason is {@code OTHER}.
 */
public record ModerationDecisionRequest(
        @NotNull
        @Schema(description = "Why. Shown, translated, to the seller.")
        ModerationReason reasonCode,

        @Size(max = MAX_NOTE_LENGTH)
        @Schema(description = "Internal detail for other moderators. Required when reasonCode is OTHER.")
        String note) {

    /** Mirrors ck_moderation_note. */
    public static final int MAX_NOTE_LENGTH = 2000;
}
