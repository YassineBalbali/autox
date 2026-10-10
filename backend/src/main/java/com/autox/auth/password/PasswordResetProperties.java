package com.autox.auth.password;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Password reset policy, bound from {@code autox.auth.password-reset.*}.
 *
 * @param ttl how long a reset link works. Short on purpose: the link is a bearer
 *            credential sitting in an inbox, and a user who asks for one acts on it within
 *            minutes. Too short and a slow mail relay makes the product look broken, which
 *            is why this is thirty minutes and not five.
 * @param maxRequestsPerWindow requests accepted for one account per {@link #window}.
 *            Without a cap, the endpoint is a way to flood someone else's inbox — the
 *            caller does not have to own the address they type in.
 * @param maxRequestsPerAddressPerWindow requests accepted from one client address per
 *            window, which is the limit that bounds enumeration sweeps.
 * @param window the period both caps are measured over.
 */
@ConfigurationProperties(prefix = "autox.auth.password-reset")
public record PasswordResetProperties(

        Duration ttl,
        int maxRequestsPerWindow,
        int maxRequestsPerAddressPerWindow,
        Duration window) {

    public PasswordResetProperties {
        if (ttl == null || ttl.isNegative() || ttl.isZero()) {
            ttl = Duration.ofMinutes(30);
        }
        if (window == null || window.isNegative() || window.isZero()) {
            window = Duration.ofHours(1);
        }
        if (maxRequestsPerWindow <= 0) {
            maxRequestsPerWindow = 3;
        }
        if (maxRequestsPerAddressPerWindow <= 0) {
            maxRequestsPerAddressPerWindow = 20;
        }
    }
}
