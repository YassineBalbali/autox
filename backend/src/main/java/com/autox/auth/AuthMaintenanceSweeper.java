package com.autox.auth;

import com.autox.auth.password.PasswordResetService;
import com.autox.auth.throttle.LoginThrottleService;
import com.autox.scheduling.ScheduledTaskCoordinator;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Housekeeping for the authentication tables.
 *
 * <p>Three things accumulate if nothing removes them: a throttle counter per address anyone
 * ever mistyped a password from, a refresh-token row per session ever established, and a
 * reset-token row per request. None of them affects correctness — stale counters are already
 * ignored, and an expired token is already refused — so this is purely about not keeping
 * rows whose only remaining purpose is to be counted.
 *
 * <p>Replaces the former {@code LoginThrottleSweeper}, which swept one of the three. The
 * other two were simply never swept: {@code deleteExpiredBefore} existed on the refresh
 * token repository with no caller.
 */
@Component
public class AuthMaintenanceSweeper {

    private static final Logger log = LoggerFactory.getLogger(AuthMaintenanceSweeper.class);

    /**
     * How long a dead credential is kept. Long enough to answer "when did that session
     * end?" during an incident, short enough that the table stays about live sessions.
     */
    private static final Duration AUDIT_RETENTION = Duration.ofDays(30);

    private final LoginThrottleService throttle;
    private final RefreshTokenService refreshTokens;
    private final PasswordResetService passwordReset;
    private final ScheduledTaskCoordinator coordinator;

    public AuthMaintenanceSweeper(LoginThrottleService throttle,
                                  RefreshTokenService refreshTokens,
                                  PasswordResetService passwordReset,
                                  ScheduledTaskCoordinator coordinator) {
        this.throttle = throttle;
        this.refreshTokens = refreshTokens;
        this.passwordReset = passwordReset;
        this.coordinator = coordinator;
    }

    /**
     * Hourly, offset from the hour so it does not pile onto other scheduled work, and on
     * one instance at a time. Each statement here is an idempotent DELETE and would survive
     * being run twice; it is coordinated anyway, because "this one is safe" is a property
     * that quietly stops being true.
     */
    @Scheduled(cron = "0 17 * * * *")
    public void purge() {
        coordinator.runExclusively("auth-maintenance", () -> {
            int counters = throttle.purgeStaleCounters();
            if (counters > 0) {
                log.debug("Purged {} stale throttle counter(s)", counters);
            }

            Instant before = Instant.now().minus(AUDIT_RETENTION);
            int sessions = refreshTokens.purgeExpiredBefore(before);
            if (sessions > 0) {
                log.debug("Purged {} long-expired refresh token(s)", sessions);
            }

            int resets = passwordReset.purgeExpiredTokens(before);
            if (resets > 0) {
                log.debug("Purged {} long-expired password reset token(s)", resets);
            }
        });
    }
}
