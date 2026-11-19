package com.autox.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * An answer: its blocks, in the order they are shown, and the state to send with the next turn.
 */
@Schema(name = "AssistantTurnResponse")
public record TurnResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AssistantBlock> blocks,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TurnRequest.State state) {
}
