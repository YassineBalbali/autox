package com.autox.assistant;

/**
 * The planner gave no plan: a timeout, the provider's quota, an error, an answer that is not
 * JSON. Every one ends the same way for the user, 503 {@code ASSISTANT_UNAVAILABLE}.
 *
 * @see com.autox.api.error.ApiException#assistantUnavailable()
 */
public class PlannerUnavailableException extends RuntimeException {

    /** Tokens the failed call still spent, when the provider said; counted against the budget. */
    private final long tokensSpent;

    public PlannerUnavailableException(String message, Throwable cause) {
        this(message, cause, 0);
    }

    public PlannerUnavailableException(String message, Throwable cause, long tokensSpent) {
        super(message, cause);
        this.tokensSpent = tokensSpent;
    }

    public long tokensSpent() {
        return tokensSpent;
    }
}
