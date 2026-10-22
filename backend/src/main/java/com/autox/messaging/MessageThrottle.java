package com.autox.messaging;

import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caps how much one account may write: {@code MESSAGE_SEND} on every message, and
 * {@code CONVERSATION_START}, lower, on every new conversation. On the same counter table as
 * the other throttles, keyed by user id rather than client address — sending needs an
 * account, and an account is what a spammer has to spend.
 *
 * <p>Like the other throttles this <b>returns a decision and does not throw</b>, and runs in
 * its own transaction, so a refusal thrown by the caller cannot roll the count back.
 *
 * <p>Called only once a message has passed every other check, so a message refused for
 * being empty, or for a listing that is no longer online, is not counted.
 */
@Service
public class MessageThrottle {

    private static final Logger log = LoggerFactory.getLogger(MessageThrottle.class);

    private final ThrottleCounters counters;
    private final MessagingThrottleProperties properties;

    public MessageThrottle(ThrottleCounters counters, MessagingThrottleProperties properties) {
        this.counters = counters;
        this.properties = properties;
    }

    /**
     * Counts one message, and one conversation if it starts one, and says whether it may be
     * sent. A refused new conversation is not also counted as a message.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision record(UUID senderId, boolean startsConversation) {
        if (!properties.enabled()) {
            return Decision.ALLOWED;
        }
        Instant now = Instant.now();
        String key = senderId.toString();

        if (startsConversation) {
            ThrottleCounters.WindowCount started = counters.recordInFixedWindow(
                    ThrottleScope.CONVERSATION_START, key, now, properties.conversationWindow());
            if (started.eventCount() > properties.conversationsPerWindow()) {
                log.warn("Refusing a new conversation from {}: {} started in the window", key, started.eventCount());
                return refuse(now, started.windowEndsAt());
            }
        }

        ThrottleCounters.WindowCount sent = counters.recordInFixedWindow(
                ThrottleScope.MESSAGE_SEND, key, now, properties.messageWindow());
        if (sent.eventCount() > properties.messagesPerWindow()) {
            log.warn("Refusing a message from {}: {} sent in the window", key, sent.eventCount());
            return refuse(now, sent.windowEndsAt());
        }
        return Decision.ALLOWED;
    }

    private static Decision refuse(Instant now, Instant windowEndsAt) {
        // Rounded up, so a client that waits exactly this long is past the window.
        return new Decision(false, Math.max(1, Duration.between(now, windowEndsAt).toSeconds() + 1));
    }

    /** Allowed, or refused with the seconds to wait. */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);
    }
}
