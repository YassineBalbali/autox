package com.autox.search.interpret;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Which optional search features this server has. A client shows an entry only when its flag
 * is true, so a feature turned off by configuration is absent rather than broken.
 *
 * @param interpret whether {@code POST /api/v1/search/interpret} is configured
 */
@Schema(description = "Optional search features, and whether this server has them.")
public record SearchCapabilities(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean interpret) {
}
