package com.autox.auth.throttle;

import com.autox.api.error.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Progressive throttling of login attempts.
 *
 * <p>Every write runs in its own transaction ({@code REQUIRES_NEW}). A failed login
 * ends in a thrown exception, and a counter incremented in the caller's transaction
 * would be rolled back with it — leaving the throttle permanently at zero. That is the
 * same trap that made refresh-token replay detection ineffective, so it is worth
 * naming twice.
 *
 * <p>Two properties this design holds on to:
 * <ul>
 *   <li><b>No enumeration.</b> The account counter is keyed by a hash of the submitted
 *       email whether or not an account exists, so a throttled response says nothing
 *       about whether the address is registered.</li>
 *   <li><b>No lockout.</b> The account delay is capped, and a successful login clears
 *       the counter, so failing on purpose cannot keep the real owner out for longer
 *       than that cap.</li>
 * </ul>
 */
@Service
public class LoginThrottleService {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleService.class);

    private final LoginAttemptCounters counters;
    private final LoginThrottleProperties properties;

    public LoginThrottleService(LoginAttemptCounters counters, LoginThrottleProperties properties) {
        this.counters = counters;
        this.properties = properties;
    }

    /**
     * Called before any password comparison, so a throttled attempt costs neither a
     * BCrypt hash nor a lookup.
     *
     * @throws ApiException 429, carrying the remaining wait, if either scope is blocked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void checkAllowed(String email, String ipAddress) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = Instant.now();
        requireNotBlocked(ThrottleScope.ACCOUNT, accountKey(email), now);
        if (ipAddress != null) {
            requireNotBlocked(ThrottleScope.IP, ipAddress, now);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String email, String ipAddress) {
        if (!properties.enabled()) {
            return;
        }
        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.resetAfter());

        int accountFailures = counters.recordFailure(ThrottleScope.ACCOUNT, accountKey(email), now, staleBefore);
        applyDelay(ThrottleScope.ACCOUNT, accountKey(email), properties.accountDelayFor(accountFailures), now);

        if (ipAddress != null) {
            int ipFailures = counters.recordFailure(ThrottleScope.IP, ipAddress, now, staleBefore);
            Duration ipDelay = properties.ipDelayFor(ipFailures);
            applyDelay(ThrottleScope.IP, ipAddress, ipDelay, now);
            if (!ipDelay.isZero()) {
                log.warn("Throttling login attempts from {} after {} failures (wait {}s)",
                        ipAddress, ipFailures, ipDelay.toSeconds());
            }
        }
    }

    /**
     * Clears the account counter only. The IP counter is left to decay on its own —
     * otherwise an attacker could reset it by logging into an account they do own.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(String email) {
        if (!properties.enabled()) {
            return;
        }
        counters.clear(ThrottleScope.ACCOUNT, accountKey(email));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int purgeStaleCounters() {
        return counters.deleteOlderThan(Instant.now().minus(properties.resetAfter()));
    }

    private void requireNotBlocked(ThrottleScope scope, String key, Instant now) {
        Optional<LoginAttemptCounters.Counter> counter = counters.find(scope, key);
        if (counter.isEmpty()) {
            return;
        }
        Instant blockedUntil = counter.get().blockedUntil();
        if (blockedUntil == null || !blockedUntil.isAfter(now)) {
            return;
        }
        // Round up, so a client that waits exactly this many seconds is past the block.
        long retryAfter = Math.max(1, Duration.between(now, blockedUntil).toSeconds() + 1);
        throw ApiException.tooManyLoginAttempts(retryAfter);
    }

    private void applyDelay(ThrottleScope scope, String key, Duration delay, Instant now) {
        counters.applyBlock(scope, key, delay.isZero() ? null : now.plus(delay));
    }

    /**
     * SHA-256 of the lower-cased address. Hashed so the throttle table is not a second
     * place that holds every email anyone has ever typed into the login form, including
     * addresses with no account.
     */
    private static String accountKey(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the JDK", impossible);
        }
    }
}
