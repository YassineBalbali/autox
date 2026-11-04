package com.autox.mail;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Tells the poller that a transaction has just committed mail. A hint and nothing more: the rows
 * are the truth, and a hint that is lost, in a restart or on another instance, costs a delay up to
 * the poller's idle check, never an email.
 *
 * <p>Its own bean because {@link EmailOutbox} is what sets it and {@link OutboxPoller}, which
 * needs the outbox, is what reads it.
 */
@Component
class OutboxSignal {

    private final AtomicBoolean waiting = new AtomicBoolean();

    void mailWaiting() {
        waiting.set(true);
    }

    /** Whether mail was committed since the last time this was asked. */
    boolean takeMailWaiting() {
        return waiting.getAndSet(false);
    }
}
