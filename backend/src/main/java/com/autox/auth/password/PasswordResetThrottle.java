package com.autox.auth.password;

import com.autox.common.Sha256;
import com.autox.throttle.ClientAddress;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Caps how often a password reset can be requested, per account and per caller.
 *
 * <p>The endpoint sends mail to an address the caller need not own, so it is abusable in two
 * directions: as a way to bury someone's inbox, and as a way to sweep a list of addresses.
 * One cap each.
 *
 * <p>Like {@code PublicRateLimitService} and for the same reason, this <b>returns a decision
 * and does not throw</b>: the counter is written here, and an exception thrown from inside
 * the same transaction would roll that write back and leave the cap stuck at one for ever.
 * It also runs in its own transaction, so a refusal thrown by the caller afterwards cannot
 * take the count with it.
 */
@Service
public class PasswordResetThrottle {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetThrottle.class);

    private final ThrottleCounters counters;
    private final PasswordResetProperties properties;

    public PasswordResetThrottle(ThrottleCounters counters, PasswordResetProperties properties) {
        this.counters = counters;
        this.properties = properties;
    }

    /**
     * Counts one request and says whether it may proceed.
     *
     * @param email     the submitted address, hashed before it is stored as a key
     * @param ipAddress the caller's address, may be {@code null}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Decision record(String email, String ipAddress) {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.window());

        String accountKey = emailKey(email);
        int perAccount = counters.recordEvent(ThrottleScope.PASSWORD_RESET, accountKey, now, staleBefore)
                .eventCount();
        if (perAccount > properties.maxRequestsPerWindow()) {
            log.warn("Refusing a password reset request: {} already made in the last {}s for one account",
                    perAccount, properties.window().toSeconds());
            return refused(now);
        }

        String addressKey = ClientAddress.throttleKey(ipAddress);
        if (addressKey != null) {
            int perAddress = counters.recordEvent(ThrottleScope.PASSWORD_RESET_IP, addressKey, now, staleBefore)
                    .eventCount();
            if (perAddress > properties.maxRequestsPerAddressPerWindow()) {
                log.warn("Refusing a password reset request: {} already made from {} in the last {}s",
                        perAddress, addressKey, properties.window().toSeconds());
                return refused(now);
            }
        }

        return Decision.ALLOWED;
    }

    /**
     * Retry after the rest of the window. The exact remainder is not computed from the
     * counter's first event: that would tell the caller when the account was last asked
     * about, which is a slow way of asking whether it exists.
     */
    private Decision refused(Instant now) {
        long retryAfter = Math.max(1, Duration.between(now, now.plus(properties.window())).toSeconds());
        return new Decision(false, retryAfter);
    }

    /**
     * SHA-256 of the lower-cased address, so this table does not become a second register
     * of every address anyone has ever typed into a reset form.
     */
    private static String emailKey(String email) {
        return Sha256.hex(email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
    }

    public record Decision(boolean allowed, long retryAfterSeconds) {

        static final Decision ALLOWED = new Decision(true, 0);
    }
}
