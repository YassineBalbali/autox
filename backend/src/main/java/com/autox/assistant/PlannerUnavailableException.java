package com.autox.assistant;

/**
 * The planner gave no plan. A {@link Kind#TIMEOUT} is asked once more, and two are 503
 * {@code ASSISTANT_SLOW}; every other kind is 503 {@code ASSISTANT_UNAVAILABLE} at once.
 *
 * @see com.autox.api.error.ApiException#assistantSlow()
 * @see com.autox.api.error.ApiException#assistantUnavailable()
 */
public class PlannerUnavailableException extends RuntimeException {

    /** Why, for the log and the turn. The message says the rest, never the provider's own text. */
    public enum Kind {
        /** No answer within {@code autox.assistant.timeout}. */
        TIMEOUT,
        /** The provider could not be reached at all. */
        UNREACHABLE,
        /** The provider answered an HTTP error other than its rate limit. */
        HTTP_ERROR,
        /** An answer that is not the provider's JSON, or has no message in it. */
        MALFORMED
    }

    private final Kind kind;
    /** Tokens the failed call still spent, when the provider said; counted against the budget. */
    private final long tokensSpent;

    public PlannerUnavailableException(String message, Throwable cause) {
        this(Kind.HTTP_ERROR, message, cause, 0);
    }

    public PlannerUnavailableException(String message, Throwable cause, long tokensSpent) {
        this(Kind.HTTP_ERROR, message, cause, tokensSpent);
    }

    public PlannerUnavailableException(Kind kind, String message, Throwable cause, long tokensSpent) {
        super(message, cause);
        this.kind = kind;
        this.tokensSpent = tokensSpent;
    }

    public Kind kind() {
        return kind;
    }

    public long tokensSpent() {
        return tokensSpent;
    }
}
