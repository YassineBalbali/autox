package com.autox.demo;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether an account is one of the demo seed's sellers, which nobody can sign in to and so
 * nobody will answer a message sent to.
 *
 * <p>Not tied to the {@code demo} profile, unlike the rest of this package: the profile is on
 * only for the deploy that seeds, and the listings stay online long after it is off. The
 * answer comes from {@link DemoLedger}'s table, which lists by id the accounts the seed
 * created, rather than from a guess at their addresses. A database that was never seeded has
 * no such table, and then no account is a demo one.
 */
@Component
public class DemoAccounts {

    private final JdbcTemplate jdbc;

    public DemoAccounts(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isDemoAccount(UUID userId) {
        // Two statements, because a query naming a table that does not exist fails to plan,
        // whatever branch of it would have run.
        Boolean seeded = jdbc.queryForObject(
                "SELECT to_regclass('public.demo_seed_ledger') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(seeded)) {
            return false;
        }
        Boolean demo = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM demo_seed_ledger WHERE entity_type = ? AND entity_id = ?)",
                Boolean.class, DemoLedger.USER, userId);
        return Boolean.TRUE.equals(demo);
    }
}
