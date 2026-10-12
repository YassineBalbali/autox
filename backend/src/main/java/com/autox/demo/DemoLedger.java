package com.autox.demo;

import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The list of every row the demo seed created, kept in the database beside them.
 *
 * <p>This is what makes removal exact. Without it, "remove the demo data" has to guess —
 * accounts whose address ends a certain way, makes with a certain slug — and a guess is
 * wrong on the day a real vehicle catalogue is loaded with a make called {@code peugeot}
 * in it. With it, removal deletes by primary key and cannot reach a row it did not make.
 *
 * <p>The table is created here at runtime rather than by a Flyway migration, on purpose:
 * a migration would put a demo table into every production database for the sake of a
 * development convenience. It is created when the demo is seeded and dropped when the
 * demo is removed, so its presence means "this database contains demo data" and nothing
 * else — which is also a quick way to check a database you are unsure about.
 */
@Component
@Profile("demo")
class DemoLedger {

    static final String USER = "USER";
    static final String MAKE = "MAKE";
    static final String MODEL = "MODEL";
    static final String LISTING = "LISTING";

    private final JdbcTemplate jdbc;

    DemoLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void create() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS demo_seed_ledger (
                    entity_type VARCHAR(20) NOT NULL,
                    entity_id   UUID        NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (entity_type, entity_id)
                )""");
    }

    boolean exists() {
        Boolean present = jdbc.queryForObject(
                "SELECT to_regclass('public.demo_seed_ledger') IS NOT NULL", Boolean.class);
        return Boolean.TRUE.equals(present);
    }

    boolean hasEntries() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM demo_seed_ledger", Integer.class);
        return count != null && count > 0;
    }

    void record(String type, UUID id) {
        jdbc.update("INSERT INTO demo_seed_ledger (entity_type, entity_id) VALUES (?, ?)", type, id);
    }

    /** In the order they were created. */
    List<UUID> ids(String type) {
        return jdbc.queryForList(
                "SELECT entity_id FROM demo_seed_ledger WHERE entity_type = ? ORDER BY created_at, entity_id",
                UUID.class, type);
    }

    void drop() {
        jdbc.execute("DROP TABLE IF EXISTS demo_seed_ledger");
    }
}
