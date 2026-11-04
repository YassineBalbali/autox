package com.autox.auth.verification;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Verifying an address, bound from {@code autox.auth.email-verification.*}.
 *
 * @param ttl                 how long a link works
 * @param resendsPerWindow    links one account may ask for again per {@code resendWindow}
 * @param resendWindow        the window for {@code resendsPerWindow}
 */
@ConfigurationProperties(prefix = "autox.auth.email-verification")
public record EmailVerificationProperties(Duration ttl, int resendsPerWindow, Duration resendWindow) {

    public EmailVerificationProperties {
        ttl = ttl == null || ttl.isNegative() || ttl.isZero() ? Duration.ofHours(24) : ttl;
        resendsPerWindow = resendsPerWindow <= 0 ? 3 : resendsPerWindow;
        resendWindow = resendWindow == null || resendWindow.isNegative() || resendWindow.isZero()
                ? Duration.ofHours(1) : resendWindow;
    }
}
