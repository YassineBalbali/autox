package com.autox.auth.throttle;

import com.autox.throttle.ClientAddress;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caps how many accounts one client address may try to create.
 *
 * <p>Registration is the only unauthenticated endpoint that both writes a row and costs a
 * password hash, so without a cap it is a way to mass-produce accounts and a way to
 * exhaust a CPU. The count is per address, on the same counter table and with the same
 * key as the login throttle — an IPv4 address, or the /64 of an IPv6 one.
 *
 * <p><b>Every attempt counts, including the ones that fail</b> — an address already
 * registered, a body that does not validate once it reaches here. Counting only successes
 * would leave the endpoint free to use as a test of which addresses have accounts, and
 * free to burn hashes on.
 *
 * <p>Like the other throttles this <b>returns a decision and does not throw</b>, and runs
 * in its own transaction: the counter is written here, and a refusal thrown by the caller
 * afterwards must not roll the count back.
 *
 * <p>It is only as good as the address it is given. Behind a proxy that does not overwrite
 * {@code X-Forwarded-For}, a caller chooses their own key — see the README, "Deploying
 * behind a proxy".
 */
@Service
public class RegistrationThrottle {

    private static final Logger log = LoggerFactory.getLogger(RegistrationThrottle.class);

    private final ThrottleCounters counters;
    private final RegistrationThrottleProperties properties;

    public RegistrationThrottle(ThrottleCounters counters, RegistrationThrottleProperties properties) {
        this.counters = counters;
        this.properties = properties;
    }

    /**
     * Counts one registration attempt and says whether it may proceed.
     *
     * @param ipAddress the caller's address; with none there is nothing to key on, and the
     *                  attempt is allowed rather than every unknown caller sharing a bucket
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision record(String ipAddress) {
        if (!properties.enabled()) {
            return Decision.ALLOWED;
        }
        String key = ClientAddress.throttleKey(ipAddress);
        if (key == null) {
            return Decision.ALLOWED;
        }

        Instant now = Instant.now();
        int attempts = counters.recordEvent(ThrottleScope.REGISTRATION_IP, key, now, now.minus(properties.window()))
                .eventCount();
        if (attempts <= properties.maxAttemptsPerWindow()) {
            return Decision.ALLOWED;
        }

        log.warn("Refusing a registration: {} attempts from {} in the last {}s",
                attempts, key, properties.window().toSeconds());
        // The whole window, not its remainder: simpler, and it tells the caller nothing
        // about when the earlier attempts were made.
        return new Decision(false, Math.max(1, Duration.between(now, now.plus(properties.window())).toSeconds()));
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);
    }
}
