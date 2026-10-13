package com.autox.listing.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

/**
 * The optional body of an approval or a reinstatement: a note for the trail, nothing else.
 * Neither needs a reason code — putting a listing online is the unremarkable outcome.
 */
public record ModerationNoteRequest(
        @Size(max = ModerationDecisionRequest.MAX_NOTE_LENGTH)
        @Schema(description = "Internal detail for other moderators.")
        String note) {
}
