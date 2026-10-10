package com.autox.throttle;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-address rate limiting for the public read endpoints, on the same counter table as
 * login throttling.
 *
 * <p><b>This method does not throw.</b> It returns a decision and lets the caller refuse
 * the request. Throwing here would be the trap this codebase has hit three times already:
 * the counter is written in this transaction, and an exception thrown from inside it rolls
 * that write back, so the limiter would count to one for ever. Returning the decision
 * means the write commits before anything is refused, and no {@code REQUIRES_NEW}
 * gymnastics are needed — though the propagation is still declared, so the limiter keeps
 * its own transaction if it is ever called from inside another one.
 */
@Service
public class PublicRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(PublicRateLimitService.class);

    private final ThrottleCounters counters;
    private final PublicRateLimitProperties properties;

    public PublicRateLimitService(ThrottleCounters counters, PublicRateLimitProperties properties) {
        this.counters = counters;
        this.properties = properties;
    }

    /**
     * Counts one request from {@code ipAddress} and says whether it may proceed.
     *
     * <p>The window is fixed rather than sliding: the counter resets once an address goes
     * quiet for longer than the window. A sliding window would need per-request timestamps,
     * which is a different and far more expensive data structure for a limit whose job is
     * only to stop bulk scraping.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision record(String ipAddress) {
        if (!properties.enabled() || ipAddress == null || ipAddress.isBlank()) {
            return Decision.ALLOWED;
        }

        Instant now = Instant.now();
        ThrottleCounters.Counter counter =
                counters.recordEvent(ThrottleScope.PUBLIC_READ, ipAddress, now, now.minus(properties.window()));

        if (counter.isBlockedAt(now)) {
            return refuse(now, counter.blockedUntil());
        }
        if (counter.eventCount() > properties.requestsPerWindow()) {
            Instant blockedUntil = now.plus(properties.blockFor());
            counters.applyBlock(ThrottleScope.PUBLIC_READ, ipAddress, blockedUntil);
            log.warn("Rate limiting public reads from {} after {} requests in {}s",
                    ipAddress, counter.eventCount(), properties.window().toSeconds());
            return refuse(now, blockedUntil);
        }
        return Decision.ALLOWED;
    }

    private static Decision refuse(Instant now, Instant blockedUntil) {
        // Rounded up, so a client that waits exactly this long is past the block.
        long retryAfter = Math.max(1, Duration.between(now, blockedUntil).toSeconds() + 1);
        return new Decision(false, retryAfter);
    }

    /** Allowed, or refused with the seconds to wait. */
    public record Decision(boolean allowed, long retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);
    }
}
