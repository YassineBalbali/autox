package com.autox.assistant;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
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
 * <p>The day's tokens are also the budget's measure. Counting is best-effort: a failure to count is
 * logged, and never fails the turn.
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

    private final JdbcTemplate jdbc;

    public AssistantUsage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Tokens spent today, for the budget. */
    public long tokensToday() {
        try {
            Long spent = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(tokens_in + tokens_out), 0) FROM assistant_usage WHERE day = ?",
                    Long.class, today());
            return spent == null ? 0 : spent;
        } catch (DataAccessException e) {
            log.warn("Could not read the assistant's token count: {}", e.getMessage());
            return 0;
        }
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

    private static LocalDate today() {
        return LocalDate.now(SITE_ZONE);
    }
}
