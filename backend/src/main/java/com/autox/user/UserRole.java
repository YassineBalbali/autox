package com.autox.user;

import io.swagger.v3.oas.annotations.media.Schema;

/** Mirrors ck_app_user_role. */
@Schema(enumAsRef = true)
public enum UserRole {
    /** Private individual. Free, with a capped number of active listings. */
    PARTICULIER,
    /** Garage or dealer. Subscription tiers attach to this role later. */
    PROFESSIONNEL,
    /** Platform moderation and management. */
    ADMIN
}
