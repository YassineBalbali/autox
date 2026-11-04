package com.autox.savedsearch;

import com.autox.common.UuidV7;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** The {@code saved_search} tables. */
@Repository
public class SavedSearchRepository {

    private static final String COLUMNS = """
            id, user_id, name, criteria_version, criteria::text AS criteria, link_params::text AS link_params,
            make_label, model_label, paused_at, last_digest_at, last_visited_at, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public SavedSearchRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Row(UUID id, UUID userId, String name, int criteriaVersion, String criteria, String linkParams,
                      String makeLabel, String modelLabel, Instant pausedAt, Instant lastDigestAt,
                      Instant lastVisitedAt, Instant createdAt) {
    }

    /** An account that can be sent alerts, locked so that two runs for it take turns. */
    public record Recipient(UUID id, String email) {
    }

    /** Holds concurrent saves by one account apart, so that they cannot both pass the limit. */
    public void lockAccount(UUID userId) {
        jdbc.query("SELECT id FROM app_user WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", userId),
                rs -> { });
    }

    public int countFor(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM saved_search WHERE user_id = :user",
                new MapSqlParameterSource("user", userId), Integer.class);
        return count == null ? 0 : count;
    }

    public Row insert(UUID userId, String name, int version, String criteria, String linkParams,
                      String makeLabel, String modelLabel) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                INSERT INTO saved_search (id, user_id, name, criteria_version, criteria, link_params,
                                          make_label, model_label, last_digest_at, last_visited_at)
                VALUES (:id, :user, :name, :version, CAST(:criteria AS jsonb), CAST(:params AS jsonb),
                        :make, :model, now(), now())
                """, new MapSqlParameterSource("id", id).addValue("user", userId).addValue("name", name)
                .addValue("version", version).addValue("criteria", criteria).addValue("params", linkParams)
                .addValue("make", makeLabel).addValue("model", modelLabel));
        return find(id).orElseThrow();
    }

    public List<Row> listFor(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM saved_search WHERE user_id = :user ORDER BY created_at, id",
                new MapSqlParameterSource("user", userId), SavedSearchRepository::row);
    }

    public List<Row> activeFor(UUID userId) {
        return jdbc.query("SELECT " + COLUMNS
                        + " FROM saved_search WHERE user_id = :user AND paused_at IS NULL ORDER BY created_at, id",
                new MapSqlParameterSource("user", userId), SavedSearchRepository::row);
    }

    public Optional<Row> find(UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM saved_search WHERE id = :id",
                new MapSqlParameterSource("id", id), SavedSearchRepository::row).stream().findFirst();
    }

    public void rename(UUID id, String name) {
        jdbc.update("UPDATE saved_search SET name = :name WHERE id = :id",
                new MapSqlParameterSource("id", id).addValue("name", name));
    }

    /** Idempotent: the first pause's date stands. */
    public void pause(UUID id) {
        jdbc.update("UPDATE saved_search SET paused_at = now() WHERE id = :id AND paused_at IS NULL",
                new MapSqlParameterSource("id", id));
    }

    /** Alerts start again from now: what was published during the pause is not sent. */
    public void resume(UUID id) {
        jdbc.update("""
                UPDATE saved_search SET paused_at = NULL, last_digest_at = now()
                 WHERE id = :id AND paused_at IS NOT NULL
                """, new MapSqlParameterSource("id", id));
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM saved_search WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public void markVisited(UUID id) {
        jdbc.update("UPDATE saved_search SET last_visited_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    // ---- the digest -----------------------------------------------------------------------

    public List<UUID> accountsWithActiveSearches() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT user_id FROM saved_search WHERE paused_at IS NULL", UUID.class);
    }

    /**
     * The account, locked, if it should get alerts: its address verified, and not closed or
     * suspended. A search outlives its owner's right to receive anything, and nothing is sent then.
     */
    public Optional<Recipient> lockRecipient(UUID userId) {
        return jdbc.query("""
                SELECT id, email FROM app_user
                 WHERE id = :id AND email_verified_at IS NOT NULL AND deleted_at IS NULL
                   AND status NOT IN ('SUSPENDED', 'DELETED')
                   FOR UPDATE
                """, new MapSqlParameterSource("id", userId),
                (rs, row) -> new Recipient(rs.getObject("id", UUID.class), rs.getString("email")))
                .stream().findFirst();
    }

    /** Whether this address has had a digest since midnight in Paris, checked against the outbox. */
    public boolean digestWrittenToday(String email) {
        Boolean written = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_email
                                WHERE recipient = :email AND email_type = 'SAVED_SEARCH_DIGEST'
                                  AND created_at >= date_trunc('day', now() AT TIME ZONE 'Europe/Paris')
                                                    AT TIME ZONE 'Europe/Paris')
                """, new MapSqlParameterSource("email", email), Boolean.class);
        return Boolean.TRUE.equals(written);
    }

    public void recordSent(UUID savedSearchId, List<UUID> listingIds) {
        MapSqlParameterSource[] rows = listingIds.stream()
                .map(listing -> new MapSqlParameterSource("search", savedSearchId).addValue("listing", listing))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate("""
                INSERT INTO saved_search_sent (saved_search_id, listing_id) VALUES (:search, :listing)
                ON CONFLICT DO NOTHING
                """, rows);
    }

    public void markDigested(UUID savedSearchId) {
        jdbc.update("UPDATE saved_search SET last_digest_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", savedSearchId));
    }

    // ---- the catalogue --------------------------------------------------------------------

    public Optional<String> makeName(UUID id) {
        return name("vehicle_make", id);
    }

    public Optional<String> modelName(UUID id) {
        return name("vehicle_model", id);
    }

    private Optional<String> name(String table, UUID id) {
        return jdbc.query("SELECT name FROM " + table + " WHERE id = :id", new MapSqlParameterSource("id", id),
                (rs, row) -> rs.getString(1)).stream().findFirst();
    }

    private static Row row(ResultSet rs, int number) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("name"),
                rs.getInt("criteria_version"),
                rs.getString("criteria"),
                rs.getString("link_params"),
                rs.getString("make_label"),
                rs.getString("model_label"),
                instant(rs.getTimestamp("paused_at")),
                instant(rs.getTimestamp("last_digest_at")),
                instant(rs.getTimestamp("last_visited_at")),
                instant(rs.getTimestamp("created_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
