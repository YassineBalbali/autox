package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/** The three lists a moderator works from. */
@Schema(enumAsRef = true)
public enum ModerationView {

    /** Online and not yet looked at, oldest first: the queue. */
    PENDING,

    /** Everything online, reviewed or not, newest first: where a takedown starts. */
    LIVE,

    /** Refused or taken down by a moderator, most recent first: where a reinstatement starts. */
    REMOVED
}
