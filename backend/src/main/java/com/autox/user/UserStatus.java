package com.autox.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_app_user_status. */
@Schema(enumAsRef = true)
public enum UserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    DELETED
}
