package com.autox.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * How the assistant is used, per Paris day (V31): turns, failures, retries, tokens, and how many
 * times each intent and each reason came up. Counts only: no table here has a column a message, a
 * plan or an argument could be put in (docs/assistant-plan.md, 6.3).
 *
 * <p>The budget is measured on the tokens of the last 24 hours, counted by the hour (V32), as Groq
 * measures its daily quota: a budget reset at midnight would let turns start again into Groq's own
 * refusal. Counting is best-effort: a failure to count is logged, and never fails the turn.
 */
@Component
public class AssistantUsage {

    private static final Logger log = LoggerFactory.getLogger(AssistantUsage.class);

    /** The site's day, as for the smart search's counts. */
    static final ZoneId SITE_ZONE = ZoneId.of("Europe/Paris");

    public enum Outcome {
        /** An answer was given, whatever it said. */
        ANSWERED,
        /** 503: the planner timed out, refused for quota or failed. */
        FAILED,
        /** Two invalid plans: the user was asked to rephrase. */
        INVALID
    }

    /** The budget's window, as Groq's daily quota. */
    static final Duration WINDOW = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AssistantUsage(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * The seconds until the tokens of the last 24 hours fall under the budget, when they are not
     * under it now: the hour whose tokens bring it under leaves the window 24 hours after it began.
     * An hour counts while it began within the last 24 hours, so a turn is refused up to an hour
     * longer than Groq would, never shorter.
     */
    public OptionalLong budgetFreesInSeconds(long budget) {
        Instant now = clock.instant();
        List<Map<String, Object>> hours;
        try {
            hours = jdbc.queryForList(
                    "SELECT hour, tokens FROM assistant_token_hour WHERE hour > ? ORDER BY hour",
                    now.minus(WINDOW).atOffset(ZoneOffset.UTC));
        } catch (DataAccessException e) {
            log.warn("Could not read the assistant's token count: {}", e.getMessage());
            return OptionalLong.empty();
        }
        long spent = hours.stream().mapToLong(row -> ((Number) row.get("tokens")).longValue()).sum();
        for (Map<String, Object> row : hours) {
            if (spent < budget) {
                break;
            }
            spent -= ((Number) row.get("tokens")).longValue();
            if (spent < budget) {
                Instant frees = instant(row.get("hour")).plus(WINDOW);
                return OptionalLong.of(Math.max(1, (Duration.between(now, frees).toMillis() + 999) / 1000));
            }
        }
        return OptionalLong.empty();
    }

    private static Instant instant(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime offset) {
            return offset.toInstant();
        }
        return ((java.time.temporal.TemporalAccessor) value).query(Instant::from);
    }

    /**
     * @param intent  the plan's intent, or null when there was none
     * @param reasons the answer's reasons for giving none, if any
     * @param retries 1 when the planner was asked twice
     */
    public void record(Outcome outcome, Plan.Intent intent, List<Plan.Reason> reasons, int retries,
                       long tokensIn, long tokensOut) {
        LocalDate day = today();
        try {
            if (tokensIn + tokensOut > 0) {
                java.time.OffsetDateTime hour = clock.instant().truncatedTo(ChronoUnit.HOURS).atOffset(ZoneOffset.UTC);
                jdbc.update("INSERT INTO assistant_token_hour (hour, tokens) VALUES (?, ?) "
                        + "ON CONFLICT (hour) DO UPDATE SET tokens = assistant_token_hour.tokens + EXCLUDED.tokens",
                        hour, tokensIn + tokensOut);
                // A day is kept past the window, for a look at the log; older hours say nothing.
                jdbc.update("DELETE FROM assistant_token_hour WHERE hour < ?", hour.minus(WINDOW.multipliedBy(2)));
            }
            jdbc.update("""
                    INSERT INTO assistant_usage (day, turns, answered, failed, invalid_plans, retries, tokens_in, tokens_out)
                    VALUES (?, 1, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (day) DO UPDATE SET
                        turns = assistant_usage.turns + 1,
                        answered = assistant_usage.answered + EXCLUDED.answered,
                        failed = assistant_usage.failed + EXCLUDED.failed,
                        invalid_plans = assistant_usage.invalid_plans + EXCLUDED.invalid_plans,
                        retries = assistant_usage.retries + EXCLUDED.retries,
                        tokens_in = assistant_usage.tokens_in + EXCLUDED.tokens_in,
                        tokens_out = assistant_usage.tokens_out + EXCLUDED.tokens_out
                    """, day, outcome == Outcome.ANSWERED ? 1 : 0, outcome == Outcome.FAILED ? 1 : 0,
                    outcome == Outcome.INVALID ? 1 : 0, retries, tokensIn, tokensOut);
            if (intent != null) {
                code(day, "INTENT_" + intent.name());
            }
            for (Plan.Reason reason : reasons) {
                code(day, "REASON_" + reason.name());
            }
        } catch (DataAccessException e) {
            log.warn("Could not count an assistant turn: {}", e.getMessage());
        }
    }

    private void code(LocalDate day, String code) {
        jdbc.update("""
                INSERT INTO assistant_usage_code (day, code, occurrences) VALUES (?, ?, 1)
                ON CONFLICT (day, code) DO UPDATE SET occurrences = assistant_usage_code.occurrences + 1
                """, day, code);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SITE_ZONE));
    }
}
