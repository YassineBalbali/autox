package com.autox.auth.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Progressive login throttling, bound from {@code autox.auth.login-throttle.*}.
 *
 * <p>Two independent scopes. The account scope stops a password-guessing run against
 * one address; the IP scope stops a spray across many addresses from one source. The
 * account delay is capped deliberately low: the point is to make guessing
 * uneconomical, not to let an attacker lock a real user out of their own account by
 * failing on purpose.
 */
@ConfigurationProperties(prefix = "autox.auth.login-throttle")
public record LoginThrottleProperties(

        boolean enabled,

        /** Consecutive failures allowed before any delay applies. */
        int accountThreshold,
        /** Delay imposed by the first failure past the threshold; doubles after that. */
        Duration accountInitialDelay,
        /** Ceiling for the account delay. A legitimate user always gets back in within this. */
        Duration accountMaxDelay,

        int ipThreshold,
        Duration ipInitialDelay,
        Duration ipMaxDelay,

        /** A counter untouched for this long is treated as cleared. */
        Duration resetAfter) {

    public LoginThrottleProperties {
        if (accountThreshold <= 0) {
            accountThreshold = 4;
        }
        if (ipThreshold <= 0) {
            ipThreshold = 20;
        }
        accountInitialDelay = orDefault(accountInitialDelay, Duration.ofSeconds(30));
        accountMaxDelay = orDefault(accountMaxDelay, Duration.ofMinutes(15));
        ipInitialDelay = orDefault(ipInitialDelay, Duration.ofMinutes(1));
        ipMaxDelay = orDefault(ipMaxDelay, Duration.ofMinutes(30));
        resetAfter = orDefault(resetAfter, Duration.ofHours(24));
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }

    /**
     * The wait required after {@code failures} consecutive failures: nothing up to the
     * threshold, then the initial delay doubling with each further failure, capped.
     */
    public static Duration delayFor(int failures, int threshold, Duration initial, Duration max) {
        if (failures <= threshold) {
            return Duration.ZERO;
        }
        // Doubled by iteration rather than by 2^n, so a long-running attack cannot
        // overflow the multiplication.
        Duration delay = initial;
        for (int step = failures - threshold - 1; step > 0 && delay.compareTo(max) < 0; step--) {
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(max) > 0 ? max : delay;
    }

    public Duration accountDelayFor(int failures) {
        return delayFor(failures, accountThreshold, accountInitialDelay, accountMaxDelay);
    }

    public Duration ipDelayFor(int failures) {
        return delayFor(failures, ipThreshold, ipInitialDelay, ipMaxDelay);
    }
}
