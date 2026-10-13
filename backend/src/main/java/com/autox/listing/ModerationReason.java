package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Why a moderator refused a listing or took it down. Stored by name in
 * {@code moderation_action.reason_code}.
 *
 * <p>A closed list rather than free text, for two reasons. The seller is told the reason,
 * and a code can be said in their language where a moderator's note cannot. And "how many
 * listings were refused as duplicates last month" is a question a code answers and a
 * sentence does not.
 *
 * <p>Codes are stable: one that has been written to the trail is never renamed or removed,
 * only retired from the screens that offer it.
 */
@Schema(enumAsRef = true)
public enum ModerationReason {

    /** Advance-payment scams, prices that cannot be real, a vehicle the seller does not have. */
    SUSPECTED_FRAUD,

    /** The description, mileage, year or photos do not match the vehicle. */
    MISLEADING_INFORMATION,

    /** Already listed by the same seller. */
    DUPLICATE,

    /** Not a vehicle for sale: parts, services, a wanted ad. */
    NOT_A_VEHICLE,

    /** Photos that are not of the vehicle, are unusable, or should not be published. */
    INAPPROPRIATE_PHOTOS,

    /** Insults, discrimination, or anything else the terms of use forbid in the text. */
    PROHIBITED_CONTENT,

    /** A phone number, an address or a link placed in the title or description. */
    CONTACT_DETAILS_IN_TEXT,

    /** Anything else. The only reason that requires a note, since the code alone says nothing. */
    OTHER;

    /** Whether the code is meaningless without an explanation beside it. */
    public boolean requiresNote() {
        return this == OTHER;
    }
}
