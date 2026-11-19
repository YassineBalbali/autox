package com.autox.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * What turns a user's message into a plan: the intent, the tool calls and their arguments
 * (docs/assistant-plan.md, 2.1). The plan is unchecked JSON; {@link PlanValidator} reads it.
 *
 * <p>Behind an interface as the smart search's interpreter is: chosen by configuration, a stub in
 * the tests, and the provider replaceable by one class. The planner never sees a tool's result,
 * a listing's title or description, or anything about a seller: only {@link Input}.
 */
public interface AssistantPlanner {

    boolean available();

    /** @throws PlannerUnavailableException on a timeout, a spent quota or any provider failure */
    Answer plan(Input input);

    /**
     * @param message     this turn's message, contact details masked
     * @param earlier     the previous messages, oldest first, at most four
     * @param references  the listings the user can point at, built from catalogue fields only
     * @param pageListing the reference of the listing the panel was opened from, or null
     * @param currentYear for "récente", as the smart search has it
     */
    record Input(String message, List<String> earlier, List<References.Reference> references, String pageListing,
                 int currentYear) {
    }

    /** @param plan the model's JSON object; the token counts are the provider's own */
    record Answer(JsonNode plan, long tokensIn, long tokensOut) {
    }
}
