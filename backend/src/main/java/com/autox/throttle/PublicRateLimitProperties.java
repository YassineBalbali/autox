package com.autox.throttle;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-IP rate limit for the unauthenticated read endpoints, bound from
 * {@code autox.public-api.rate-limit.*}.
 *
 * <p>Sized so that a real person never reaches it. A fast browsing session — search,
 * scroll, open a listing, go back, refine — is on the order of one request a second
 * including the reference lookups the Angular app makes on load. The default allows
 * five a second sustained for a minute, which is a scraper, not a visitor.
 *
 * <p>Unlike the login throttle this is a flat limit rather than a progressive delay.
 * A progressive one is right when the question is "is this the account owner getting it
 * wrong"; here there is no owner to protect and no reason to be gentle with the second
 * offence.
 */
@ConfigurationProperties(prefix = "autox.public-api.rate-limit")
public record PublicRateLimitProperties(

        boolean enabled,

        /** Requests allowed per address within {@link #window()}. */
        int requestsPerWindow,

        /** The counting window. Fixed, not sliding — see the decay note in the service. */
        Duration window,

        /** How long an address that exceeds the limit is refused. */
        Duration blockFor) {

    public PublicRateLimitProperties {
        if (requestsPerWindow <= 0) {
            requestsPerWindow = 300;
        }
        window = orDefault(window, Duration.ofMinutes(1));
        blockFor = orDefault(blockFor, Duration.ofMinutes(1));
    }

    private static Duration orDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
