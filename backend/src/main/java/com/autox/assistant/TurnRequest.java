package com.autox.assistant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * One turn of a conversation with Assistant AutoX.
 *
 * @param message what the user wrote, at most 500 characters
 * @param state   what the previous answer returned, sent back unchanged; absent on the first turn
 * @param context the page the conversation was started from, if a listing's
 */
@Schema(name = "AssistantTurnRequest")
public record TurnRequest(
        @Schema(example = "Une 208 essence de moins de 5 ans, moins de 12 000 €")
        @NotBlank @Size(max = TurnRequest.MAX_MESSAGE) String message,
        @Valid State state,
        @Valid Context context) {

    public static final int MAX_MESSAGE = 500;
    public static final int MAX_EARLIER = 4;
    public static final int MAX_TURNS = 12;

    /**
     * The conversation so far, as the server returned it. Nothing is stored on the server: the
     * client holds this and sends it back. It is checked like any input: each listing is read
     * again, and there is no field a figure or a tool's result could be put in.
     *
     * @param earlier the previous messages, oldest first
     * @param shown   the listings already shown, oldest first, which the user can point at
     * @param turns   how many turns the conversation has had
     */
    @Schema(name = "AssistantTurnState")
    public record State(
            @NotNull @Size(max = MAX_EARLIER) List<@NotBlank @Size(max = MAX_MESSAGE) String> earlier,
            @NotNull @Size(max = References.MAX) List<@NotNull UUID> shown,
            @Min(0) @Max(MAX_TURNS) int turns) {

        public static State empty() {
            return new State(List.of(), List.of(), 0);
        }
    }

    /** @param listingId the listing whose page the user is on */
    @Schema(name = "AssistantTurnContext")
    public record Context(UUID listingId) {
    }
}
