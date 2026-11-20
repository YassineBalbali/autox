package com.autox.assistant;

import com.autox.throttle.ClientAddress;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What a caller may spend (docs/assistant-plan.md, 5.3): turns per address and per day. The pace
 * of all callers together is {@link AssistantPacing}'s.
 *
 * <p>Returns decisions rather than throwing, for the reason {@code PublicRateLimitService} gives: an
 * exception inside the transaction would roll back the count it just wrote.
 */
@Service
public class AssistantLimits {

    private static final Logger log = LoggerFactory.getLogger(AssistantLimits.class);
    private static final Duration DAY = Duration.ofHours(24);

    private final ThrottleCounters counters;
    private final AssistantProperties properties;
    private final Clock clock;

    @Autowired
    public AssistantLimits(ThrottleCounters counters, AssistantProperties properties) {
        this(counters, properties, Clock.systemUTC());
    }

    AssistantLimits(ThrottleCounters counters, AssistantProperties properties, Clock clock) {
        this.counters = counters;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Counts one turn for its caller: per address in a short window, then per day, per account when
     * signed in and per address when not. Empty if it may proceed, else the seconds to wait.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OptionalLong recordCaller(String address, UUID accountId) {
        AssistantProperties.Limits limits = properties.limits();
        if (!limits.enabled()) {
            return OptionalLong.empty();
        }
        Instant now = clock.instant();
        String key = ClientAddress.throttleKey(address);
        if (key != null) {
            OptionalLong wait = count(ThrottleScope.ASSISTANT_ADDRESS, key, now, limits.addressWindow(), limits.perAddress());
            if (wait.isPresent()) {
                return wait;
            }
        }
        if (accountId != null) {
            return count(ThrottleScope.ASSISTANT_ACCOUNT_DAY, accountId.toString(), now, DAY, limits.accountPerDay());
        }
        return key == null ? OptionalLong.empty()
                : count(ThrottleScope.ASSISTANT_ANON_DAY, key, now, DAY, limits.anonymousPerDay());
    }

    private OptionalLong count(ThrottleScope scope, String key, Instant now, Duration window, int allowed) {
        ThrottleCounters.WindowCount count = counters.recordInFixedWindow(scope, key, now, window);
        if (count.eventCount() <= allowed) {
            return OptionalLong.empty();
        }
        if (count.eventCount() == allowed + 1) {
            log.warn("Assistant limit {} reached after {} turns in {}s", scope, allowed, window.toSeconds());
        }
        return OptionalLong.of(Math.max(1, Duration.between(now, count.windowEndsAt()).toSeconds() + 1));
    }
}
