package com.autox.auth.throttle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes decayed failure counters.
 *
 * <p>Without this the table grows by one row per address anyone ever mistyped. The rows
 * are already ignored once stale — this only reclaims the space.
 */
@Component
public class LoginThrottleSweeper {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleSweeper.class);

    private final LoginThrottleService throttle;

    public LoginThrottleSweeper(LoginThrottleService throttle) {
        this.throttle = throttle;
    }

    /**
     * Hourly, offset from the hour so it does not pile onto other scheduled work. Safe
     * to run on several instances at once: it is a single idempotent DELETE.
     */
    @Scheduled(cron = "0 17 * * * *")
    public void purge() {
        int removed = throttle.purgeStaleCounters();
        if (removed > 0) {
            log.debug("Purged {} stale login attempt counter(s)", removed);
        }
    }
}
