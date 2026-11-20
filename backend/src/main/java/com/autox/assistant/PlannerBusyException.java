package com.autox.assistant;

/**
 * The provider is rate limiting (Groq's 429): not a failure, a wait. The user is told the assistant
 * is busy and when to try again, and the server itself waits that long before calling again, so the
 * next users are answered at once instead of each spending a refused call.
 */
public class PlannerBusyException extends RuntimeException {

    private final long retryAfterSeconds;

    public PlannerBusyException(long retryAfterSeconds) {
        super("The planner's provider is rate limiting; retry in " + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
