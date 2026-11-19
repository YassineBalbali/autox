package com.autox.assistant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Whether Assistant AutoX can take a turn now. Only answered when the assistant is switched on:
 * off, the endpoint is a 404, which a client reads as "no assistant".
 *
 * @param available false when today's budget is spent: the entry point is shown disabled, with a
 *                  link to the ordinary search (docs/assistant-plan.md, D2)
 * @param reason    why not, when not available
 */
@Schema(name = "AssistantCapabilities")
public record AssistantCapabilities(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean available,
        Unavailable reason,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxMessageLength,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int maxTurns) {

    @Schema(name = "AssistantUnavailableReason", enumAsRef = true)
    public enum Unavailable {
        /** Today's token budget is spent, or the provider refused for quota. */
        BUSY
    }
}
