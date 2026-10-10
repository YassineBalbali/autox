package com.autox.throttle;

import com.autox.common.UuidV7;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Event counters, read and written with plain SQL. Shared by login throttling and by
 * the public-endpoint rate limit.
 *
 * <p>Deliberately not a JPA entity. The increment has to be atomic across concurrent
 * events on the same key, which {@code INSERT … ON CONFLICT DO UPDATE} gives for free
 * and a load-modify-save through Hibernate does not. It is also a counter, not a domain
 * aggregate: nothing else references it.
 */
@Repository
public class ThrottleCounters {

    private final JdbcTemplate jdbc;

    public ThrottleCounters(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Counter> find(ThrottleScope scope, String key) {
        return jdbc.query("""
                        SELECT event_count, last_event_at, blocked_until
                          FROM throttle_counter
                         WHERE scope = ? AND scope_key = ?
                        """,
                        rs -> rs.next()
                                ? Optional.of(new Counter(
                                        rs.getInt("event_count"),
                                        rs.getTimestamp("last_event_at").toInstant(),
                                        toInstant(rs.getTimestamp("blocked_until"))))
                                : Optional.empty(),
                        scope.name(), key);
    }

    /**
     * Records one event and returns the resulting count together with the block already
     * in force, in a single round trip.
     *
     * <p>{@code staleBefore} implements decay: a counter whose last event predates it
     * restarts from one instead of continuing to climb. For the login scopes that means a
     * user who fails twice a month is never throttled; for {@code PUBLIC_READ} it is what
     * makes the limit a per-window rate rather than a lifetime budget.
     *
     * <p>The existing {@code blocked_until} is returned rather than fetched first, because
     * the hot path is one request per visitor action and a second query to learn "not
     * blocked" would double the cost of the common case.
     */
    public Counter recordEvent(ThrottleScope scope, String key, Instant now, Instant staleBefore) {
        return jdbc.queryForObject("""
                INSERT INTO throttle_counter
                            (id, scope, scope_key, event_count, first_event_at, last_event_at)
                     VALUES (?, ?, ?, 1, ?, ?)
                ON CONFLICT (scope, scope_key) DO UPDATE
                        SET event_count = CASE
                                WHEN throttle_counter.last_event_at < ? THEN 1
                                ELSE throttle_counter.event_count + 1
                            END,
                            first_event_at = CASE
                                WHEN throttle_counter.last_event_at < ? THEN EXCLUDED.first_event_at
                                ELSE throttle_counter.first_event_at
                            END,
                            last_event_at = EXCLUDED.last_event_at
                  RETURNING event_count, last_event_at, blocked_until
                """,
                (rs, row) -> new Counter(
                        rs.getInt("event_count"),
                        rs.getTimestamp("last_event_at").toInstant(),
                        toInstant(rs.getTimestamp("blocked_until"))),
                UuidV7.generate(), scope.name(), key,
                Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(staleBefore), Timestamp.from(staleBefore));
    }

    public void applyBlock(ThrottleScope scope, String key, Instant blockedUntil) {
        jdbc.update("UPDATE throttle_counter SET blocked_until = ? WHERE scope = ? AND scope_key = ?",
                blockedUntil == null ? null : Timestamp.from(blockedUntil), scope.name(), key);
    }

    /** A successful login clears that account's counter outright. */
    public void clear(ThrottleScope scope, String key) {
        jdbc.update("DELETE FROM throttle_counter WHERE scope = ? AND scope_key = ?", scope.name(), key);
    }

    /** Housekeeping, so the table does not grow without bound. */
    public int deleteOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM throttle_counter WHERE last_event_at < ?", Timestamp.from(cutoff));
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record Counter(int eventCount, Instant lastEventAt, Instant blockedUntil) {

        public boolean isBlockedAt(Instant instant) {
            return blockedUntil != null && blockedUntil.isAfter(instant);
        }
    }
}
