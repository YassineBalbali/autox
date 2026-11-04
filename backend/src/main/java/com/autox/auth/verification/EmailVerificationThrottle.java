package com.autox.auth.verification;

import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caps "Renvoyer le lien" per account, since each press is an email on a limited provider plan.
 *
 * <p>Returns a decision and runs in its own transaction, like the other throttles, so that the
 * refusal thrown by the caller cannot roll the count back.
 */
@Service
public class EmailVerificationThrottle {

    private final ThrottleCounters counters;
    private final EmailVerificationProperties properties;

    public EmailVerificationThrottle(ThrottleCounters counters, EmailVerificationProperties properties) {
        this.counters = counters;
        this.properties = properties;
    }

    /** @return 0 if allowed, or the seconds to wait */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long record(UUID userId) {
        Instant now = Instant.now();
        ThrottleCounters.WindowCount count = counters.recordInFixedWindow(
                ThrottleScope.EMAIL_VERIFICATION, userId.toString(), now, properties.resendWindow());
        if (count.eventCount() <= properties.resendsPerWindow()) {
            return 0;
        }
        return Math.max(1, Duration.between(now, count.windowEndsAt()).toSeconds() + 1);
    }
}
