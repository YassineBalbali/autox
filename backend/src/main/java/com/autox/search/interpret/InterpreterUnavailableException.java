package com.autox.search.interpret;

/**
 * The provider could not answer. Every way that can happen ends the same for the visitor — the
 * ordinary search instead — so this is one exception with the reason attached for the log.
 */
public class InterpreterUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** Disabled by configuration ({@code autox.ai.provider=none}). */
        DISABLED,
        /** No answer within {@code autox.ai.timeout}. */
        TIMEOUT,
        /** The provider refused for quota: its own 429. */
        QUOTA,
        /** Anything else: a 5xx, a refused connection, an answer that is not the expected JSON. */
        PROVIDER_ERROR
    }

    private final Reason reason;

    public InterpreterUnavailableException(Reason reason, String message) {
        this(reason, message, null);
    }

    public InterpreterUnavailableException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
