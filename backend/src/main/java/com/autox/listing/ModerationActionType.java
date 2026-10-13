package com.autox.listing;

/** Mirrors ck_moderation_action. REJECT and REMOVE require a reason code. */
@io.swagger.v3.oas.annotations.media.Schema(enumAsRef = true)
public enum ModerationActionType {
    APPROVE,
    REJECT,
    REMOVE,
    REINSTATE
}
