package com.autox.messaging;

import io.swagger.v3.oas.annotations.media.Schema;

/** Which side of a conversation the caller is on. */
@Schema(enumAsRef = true)
public enum ConversationRole {

    /** Started it, about someone else's listing. */
    BUYER,

    /** The listing is theirs. */
    SELLER
}
