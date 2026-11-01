package com.autox.search.interpret;

import com.autox.throttle.ClientAddress;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The per-address cap on natural-language search, in a fixed window.
 *
 * <p>Returns a decision rather than throwing, for the reason {@code PublicRateLimitService}
 * gives: an exception thrown inside this transaction would roll back the count it just wrote,
 * and the limiter would count to one for ever.
 */
@Service
class InterpretRateLimit {

    private static final Logger log = LoggerFactory.getLogger(InterpretRateLimit.class);

    private final ThrottleCounters counters;
    private final AiProperties.RateLimit limit;

    InterpretRateLimit(ThrottleCounters counters, AiProperties properties) {
        this.counters = counters;
        this.limit = properties.rateLimit();
    }

    /** Counts one request; empty if it may proceed, else the seconds until the window closes. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalLong record(String ipAddress) {
        if (!limit.enabled()) {
            return OptionalLong.empty();
        }
        // A whole IPv6 /64 is one caller, as for every other per-address limit.
        String key = ClientAddress.throttleKey(ipAddress);
        if (key == null) {
            return OptionalLong.empty();
        }
        Instant now = Instant.now();
        ThrottleCounters.WindowCount count =
                counters.recordInFixedWindow(ThrottleScope.SEARCH_INTERPRET, key, now, limit.window());
        if (count.eventCount() <= limit.requestsPerWindow()) {
            return OptionalLong.empty();
        }
        if (count.eventCount() == limit.requestsPerWindow() + 1) {
            log.warn("Rate limiting natural-language search from {} after {} requests in {}s",
                    key, limit.requestsPerWindow(), limit.window().toSeconds());
        }
        // Rounded up, so a client that waits exactly this long is in the next window.
        return OptionalLong.of(Math.max(1, Duration.between(now, count.windowEndsAt()).toSeconds() + 1));
    }
}
