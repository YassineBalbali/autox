package com.autox.listing;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The orders a moderation list can be read in. A list's date is the one that matters to it: when
 * the listing was created for the queue, put online for "en ligne", and last changed — which is
 * when it was taken down — for "retirées".
 */
@Schema(enumAsRef = true)
public enum ModerationSort {

    /** Oldest first: how a queue is worked. The queue's default. */
    OLDEST,

    /** Newest first. The default of the two other lists. */
    NEWEST,

    PRICE_ASC,

    PRICE_DESC;

    /** What a list is read in when nothing is asked. */
    static ModerationSort defaultFor(ModerationView view) {
        return view == ModerationView.PENDING ? OLDEST : NEWEST;
    }
}
