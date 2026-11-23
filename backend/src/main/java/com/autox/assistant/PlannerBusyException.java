package com.autox.assistant;

/**
 * The provider is rate limiting (Groq's 429): not a failure, a wait. The user is told the assistant
 * is busy and when to try again, and the server itself waits that long before calling again, so the
 * next users are answered at once instead of each spending a refused call.
 */
public class PlannerBusyException extends RuntimeException {

    private final long retryAfterSeconds;
    private final boolean daily;

    public PlannerBusyException(long retryAfterSeconds) {
        this(retryAfterSeconds, false);
    }

    /** @param daily the provider's daily quota, not its minute's: the wait is hours */
    public PlannerBusyException(long retryAfterSeconds, boolean daily) {
        super("The planner's provider is rate limiting" + (daily ? " for its daily quota" : "") + "; retry in "
                + retryAfterSeconds + "s");
        this.retryAfterSeconds = retryAfterSeconds;
        this.daily = daily;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public boolean daily() {
        return daily;
    }
}
