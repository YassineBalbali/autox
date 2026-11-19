package com.autox.assistant.tools;

import java.util.Objects;
import java.util.UUID;

/**
 * What an assistant tool returns: a value, or a reason it has none.
 *
 * <p>A refusal is not an error. It is an answer the user sees as fixed text with a way
 * forward (docs/assistant-plan.md, 1.6): a listing that has gone, a car the simulator does
 * not apply to, a question the help pages do not answer.
 */
public sealed interface ToolResult<T> {

    record Answer<T>(T value) implements ToolResult<T> {
        public Answer {
            Objects.requireNonNull(value, "value");
        }
    }

    /** @param listingId the listing the reason is about, when there is one */
    record Refusal<T>(Reason reason, UUID listingId) implements ToolResult<T> {
        public Refusal {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** Why a tool has no value. The plan's other reasons (off-topic, advice) are the planner's. */
    enum Reason {
        /** Not online any more, or never was: the public API's 404. */
        LISTING_UNAVAILABLE,
        /** The site's own rule gives no figure: fewer than five comparables, Crit'Air unsettled. */
        NOT_COMPUTABLE,
        /** A leasing offer, a damaged car or no price: the simulator does not apply. */
        NOT_FINANCEABLE,
        /** The down payment covers the price: nothing to borrow. */
        NOTHING_TO_FINANCE,
        /** Nothing in the help pages answers the question. */
        NOT_IN_HELP
    }

    static <T> ToolResult<T> answer(T value) {
        return new Answer<>(value);
    }

    static <T> ToolResult<T> refusal(Reason reason) {
        return new Refusal<>(reason, null);
    }

    static <T> ToolResult<T> refusal(Reason reason, UUID listingId) {
        return new Refusal<>(reason, listingId);
    }
}
