package com.autox.listing;

/** Mirrors ck_moderation_action. REJECT and REMOVE require a reason code. */
public enum ModerationActionType {
    APPROVE,
    REJECT,
    REMOVE,
    REINSTATE
}
