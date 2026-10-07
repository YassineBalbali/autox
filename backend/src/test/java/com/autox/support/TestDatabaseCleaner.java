package com.autox.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Empties the tables tests write to, leaving the reference data seeded by {@code V5}
 * in place. Truncating is cheaper than rolling back, and unlike
 * {@code @Transactional} tests it exercises real commits — which is what the refresh
 * token rotation logic depends on.
 */
@Component
public class TestDatabaseCleaner {

    private final JdbcTemplate jdbcTemplate;

    public TestDatabaseCleaner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public void truncateTransactionalTables() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE moderation_action, listing_feature, listing_image_variant,
                               listing_image, listing, refresh_token, login_attempt_counter,
                               pro_profile, app_user
                RESTART IDENTITY CASCADE
                """);
    }
}
