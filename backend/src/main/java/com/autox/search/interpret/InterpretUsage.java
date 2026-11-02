package com.autox.search.interpret;

import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Counts natural-language search requests per day for the admin dashboard (V18). Counts only:
 * nothing here ever sees the text of a query.
 *
 * <p>Best-effort. A failure to count is logged and swallowed — a visitor's search must not fail
 * because a statistic could not be written.
 */
@Component
class InterpretUsage {

    private static final Logger log = LoggerFactory.getLogger(InterpretUsage.class);

    /** The site's day: a request at 00:30 in Paris belongs to that Paris date. */
    static final ZoneId SITE_ZONE = ZoneId.of("Europe/Paris");

    enum Outcome {
        UNDERSTOOD,
        PARTLY_UNDERSTOOD,
        FAILED
    }

    private final JdbcTemplate jdbc;

    InterpretUsage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void record(Outcome outcome) {
        int failed = outcome == Outcome.FAILED ? 1 : 0;
        int partly = outcome == Outcome.PARTLY_UNDERSTOOD ? 1 : 0;
        try {
            jdbc.update("""
                    INSERT INTO search_interpret_usage (day, requests, failed, with_not_understood)
                    VALUES (?, 1, ?, ?)
                    ON CONFLICT (day) DO UPDATE
                       SET requests = search_interpret_usage.requests + 1,
                           failed = search_interpret_usage.failed + EXCLUDED.failed,
                           with_not_understood = search_interpret_usage.with_not_understood
                                                 + EXCLUDED.with_not_understood
                    """, LocalDate.now(SITE_ZONE), failed, partly);
        } catch (DataAccessException e) {
            log.warn("Could not count a natural-language search request: {}", e.getMessage());
        }
    }
}
