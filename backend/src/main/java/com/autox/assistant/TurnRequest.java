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
     * <p>The last search and the car last described are kept so a follow-up builds on them (« moins
     * de 15 000 € », « Et sa Crit'Air ? »): the model is shown them, and the server takes from them
     * what a plan leaves out. They are the plan's own terms, read again like the rest: a search's
     * criteria go through the smart search's validator every turn, and a field that is not one of
     * its criteria is dropped.
     *
     * @param earlier the previous messages, oldest first
     * @param shown   the listings already shown, oldest first, which the user can point at
     * @param turns   how many turns the conversation has had
     * @param search  the last search's criteria, in the planner's terms; absent before any search
     * @param car     the car last described, valued or asked its Crit'Air; absent before any
     */
    @Schema(name = "AssistantTurnState")
    public record State(
            @NotNull @Size(max = MAX_EARLIER) List<@NotBlank @Size(max = MAX_MESSAGE) String> earlier,
            @NotNull @Size(max = References.MAX) List<@NotNull UUID> shown,
            @Min(0) @Max(MAX_TURNS) int turns,
            @Schema(type = "object", implementation = Object.class,
                    description = "the last search's criteria, as search_listings takes them; absent before any")
            com.fasterxml.jackson.databind.JsonNode search,
            @Valid Car car) {

        public static State empty() {
            return new State(List.of(), List.of(), 0, null, null);
        }
    }

    /** A car the user described, as estimate_value and crit_air take it; any detail may be absent. */
    @Schema(name = "AssistantTurnCar")
    public record Car(
            @Size(max = 60) String make,
            @Size(max = 60) String model,
            com.autox.catalog.FuelType fuel,
            @Min(PlanValidator.MIN_POWER_HP) @Max(PlanValidator.MAX_POWER_HP) Integer powerHp,
            @Min(PlanValidator.MIN_YEAR) @Max(2100) Integer year,
            @Min(1) @Max(12) Integer month,
            @Min(0) @Max(PlanValidator.MAX_MILEAGE_KM) Integer mileageKm) {
    }

    /** @param listingId the listing whose page the user is on */
    @Schema(name = "AssistantTurnContext")
    public record Context(UUID listingId) {
    }
}
