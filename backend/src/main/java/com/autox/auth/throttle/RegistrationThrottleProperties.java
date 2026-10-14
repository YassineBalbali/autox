package com.autox.auth.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How many accounts one client address may try to create, and over what period.
 *
 * @param enabled              off only where every caller shares one address — the test
 *                             suite — and never in a deployment
 * @param maxAttemptsPerWindow attempts allowed per address per window, successful or not
 * @param window               the period the count is taken over
 */
@ConfigurationProperties(prefix = "autox.auth.registration-throttle")
public record RegistrationThrottleProperties(
        boolean enabled,
        int maxAttemptsPerWindow,
        Duration window) {

    public RegistrationThrottleProperties {
        if (window == null || window.isNegative() || window.isZero()) {
            window = Duration.ofHours(1);
        }
        if (maxAttemptsPerWindow <= 0) {
            maxAttemptsPerWindow = 5;
        }
    }
}
