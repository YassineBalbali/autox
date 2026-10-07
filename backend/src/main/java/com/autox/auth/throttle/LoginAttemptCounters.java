package com.autox.auth.throttle;

import com.autox.common.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Failure counters, read and written with plain SQL.
 *
 * <p>Deliberately not a JPA entity. The increment has to be atomic across concurrent
 * attempts on the same key, which {@code INSERT … ON CONFLICT DO UPDATE} gives for
 * free and a load-modify-save through Hibernate does not. It is also a counter, not a
 * domain aggregate: nothing else references it.
 */
@Repository
public class LoginAttemptCounters {

    private final JdbcTemplate jdbc;

    public LoginAttemptCounters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Counter> find(ThrottleScope scope, String key) {
        return jdbc.query("""
                        SELECT failure_count, last_failure_at, blocked_until
                          FROM login_attempt_counter
                         WHERE scope = ? AND scope_key = ?
                        """,
                        rs -> rs.next()
                                ? Optional.of(new Counter(
                                        rs.getInt("failure_count"),
                                        rs.getTimestamp("last_failure_at").toInstant(),
                                        toInstant(rs.getTimestamp("blocked_until"))))
                                : Optional.empty(),
                        scope.name(), key);
    }

    /**
     * Records one failure and returns the resulting consecutive-failure count.
     *
     * <p>{@code staleBefore} implements decay: a counter whose last failure predates it
     * restarts from one instead of continuing to climb, so a user who fails twice a
     * month is never throttled.
     */
    public int recordFailure(ThrottleScope scope, String key, Instant now, Instant staleBefore) {
        return jdbc.queryForObject("""
                INSERT INTO login_attempt_counter
                            (id, scope, scope_key, failure_count, first_failure_at, last_failure_at)
                     VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT (scope, scope_key) DO UPDATE
                        SET failure_count = CASE
                                WHEN login_attempt_counter.last_failure_at < ? THEN 1
                                ELSE login_attempt_counter.failure_count + 1
                            END,
                            first_failure_at = CASE
                                WHEN login_attempt_counter.last_failure_at < ? THEN EXCLUDED.first_failure_at
                                ELSE login_attempt_counter.first_failure_at
                            END,
                            last_failure_at = EXCLUDED.last_failure_at
                  RETURNING failure_count
                """,
                Integer.class,
                UuidV7.generate(), scope.name(), key,
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(staleBefore), Timestamp.from(staleBefore));
    }

    public void applyBlock(ThrottleScope scope, String key, Instant blockedUntil) {
        jdbc.update("UPDATE login_attempt_counter SET blocked_until = ? WHERE scope = ? AND scope_key = ?",
                blockedUntil == null ? null : Timestamp.from(blockedUntil), scope.name(), key);
    }

    /** A successful login clears that account's counter outright. */
    public void clear(ThrottleScope scope, String key) {
        jdbc.update("DELETE FROM login_attempt_counter WHERE scope = ? AND scope_key = ?", scope.name(), key);
    }

    /** Housekeeping, so the table does not grow without bound. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM login_attempt_counter WHERE last_failure_at < ?", Timestamp.from(cutoff));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Counter(int failureCount, Instant lastFailureAt, Instant blockedUntil) {
    }
}
