package com.autox.admin;

import com.autox.admin.AdminUsers.AdminListingHistoryEntry;
import com.autox.admin.AdminUsers.AdminUserActivity;
import com.autox.admin.AdminUsers.AdminUserListing;
import com.autox.admin.AdminUsers.AdminUserRow;
import com.autox.listing.ListingStatus;
import com.autox.listing.ModerationActionType;
import com.autox.listing.ModerationStatus;
import com.autox.search.ListingSearchRepository;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The reads behind the administrators' user screens. Plain SQL: each is a join or a count across
 * several tables, and the demo ledger may not exist at all.
 */
@Repository
class AdminUserRepository {

    private final NamedParameterJdbcTemplate jdbc;

    AdminUserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** What the list can be narrowed by. A null or blank value narrows nothing. */
    record Filter(String search, UserRole role, UserStatus status, Boolean demo) {
    }

    Page<AdminUserRow> list(Filter filter, PageRequest page) {
        String isDemo = isDemo();
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("limit", page.getPageSize())
                .addValue("offset", page.getOffset());

        // Closed accounts stay out, as they always have: nothing here can act on them.
        StringBuilder where = new StringBuilder(" WHERE u.deleted_at IS NULL AND u.status <> 'DELETED'");
        if (filter.search() != null && !filter.search().isBlank()) {
            where.append(" AND (lower(u.display_name) LIKE :search ESCAPE '\\' OR lower(u.email) LIKE :search ESCAPE '\\')");
            parameters.addValue("search", "%" + escapeLike(filter.search().trim().toLowerCase(Locale.ROOT)) + "%");
        }
        if (filter.role() != null) {
            where.append(" AND u.role = :role");
            parameters.addValue("role", filter.role().name());
        }
        if (filter.status() != null) {
            where.append(" AND u.status = :status");
            parameters.addValue("status", filter.status().name());
        }
        if (filter.demo() != null) {
            where.append(filter.demo() ? " AND " : " AND NOT ").append(isDemo);
        }

        Long total = jdbc.queryForObject("SELECT count(*) FROM app_user u" + where, parameters, Long.class);
        List<AdminUserRow> rows = jdbc.query("""
                SELECT u.id, u.email, u.display_name, u.role, u.status, u.email_verified_at IS NOT NULL AS verified,
                       u.country_code, u.locale, u.created_at,
                       (SELECT count(*) FROM listing l WHERE l.seller_id = u.id AND l.deleted_at IS NULL) AS listings,
                       %s AS demo
                  FROM app_user u""".formatted(isDemo) + where
                        + " ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset",
                parameters,
                (rs, row) -> new AdminUserRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("email"),
                        rs.getString("display_name"),
                        UserRole.valueOf(rs.getString("role")),
                        UserStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("verified"),
                        rs.getString("country_code"),
                        rs.getString("locale"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getLong("listings"),
                        rs.getBoolean("demo")));
        return new PageImpl<>(rows, page, total == null ? 0 : total);
    }

    boolean isDemoAccount(UUID userId) {
        Boolean demo = jdbc.queryForObject("SELECT " + isDemo() + " FROM app_user u WHERE u.id = :id",
                new MapSqlParameterSource("id", userId), Boolean.class);
        return Boolean.TRUE.equals(demo);
    }

    AdminUserActivity activity(UUID userId) {
        return jdbc.queryForObject("""
                SELECT (SELECT count(*) FROM listing l WHERE l.seller_id = :id AND l.deleted_at IS NULL) AS listings,
                       (SELECT count(*) FROM listing l WHERE l.seller_id = :id AND l.deleted_at IS NULL
                           AND l.status = 'ACTIVE') AS listings_online,
                       (SELECT count(*) FROM conversation c WHERE c.buyer_id = :id OR c.seller_id = :id) AS conversations,
                       (SELECT count(*) FROM message m WHERE m.sender_id = :id) AS messages_sent,
                       (SELECT count(*) FROM message_report r WHERE r.reporter_id = :id) AS reports_filed,
                       (SELECT count(*) FROM message_report r JOIN message m ON m.id = r.message_id
                         WHERE m.sender_id = :id) AS reports_received,
                       (SELECT count(DISTINCT t.family_id) FROM refresh_token t
                         WHERE t.user_id = :id AND t.revoked_at IS NULL AND t.expires_at > now()) AS sessions
                """,
                new MapSqlParameterSource("id", userId),
                (rs, row) -> new AdminUserActivity(
                        rs.getLong("listings"),
                        rs.getLong("listings_online"),
                        rs.getLong("conversations"),
                        rs.getLong("messages_sent"),
                        rs.getLong("reports_filed"),
                        rs.getLong("reports_received"),
                        rs.getLong("sessions")));
    }

    /** Newest first. {@code online} is the public predicate itself, not a copy of it. */
    List<AdminUserListing> listings(UUID userId, int limit) {
        return jdbc.query("""
                SELECT l.id, l.title, l.status, l.moderation_status, l.price_amount, l.price_currency_code,
                       l.created_at, (%s) AS online
                  FROM listing l
                 WHERE l.seller_id = :id AND l.deleted_at IS NULL
                 ORDER BY l.created_at DESC, l.id DESC
                 LIMIT :limit""".formatted(ListingSearchRepository.visible("l")),
                new MapSqlParameterSource("id", userId).addValue("limit", limit),
                (rs, row) -> new AdminUserListing(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        ListingStatus.valueOf(rs.getString("status")),
                        ModerationStatus.valueOf(rs.getString("moderation_status")),
                        rs.getLong("price_amount"),
                        rs.getString("price_currency_code"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("online")));
    }

    /** What moderators decided about this seller's listings, newest first. */
    List<AdminListingHistoryEntry> moderationHistory(UUID userId, int limit) {
        return jdbc.query("""
                SELECT a.id, a.listing_id, l.title, a.action, a.reason_code, a.note, u.display_name, a.created_at
                  FROM moderation_action a
                  JOIN listing l ON l.id = a.listing_id
                  JOIN app_user u ON u.id = a.admin_id
                 WHERE l.seller_id = :id
                 ORDER BY a.created_at DESC, a.id DESC
                 LIMIT :limit""",
                new MapSqlParameterSource("id", userId).addValue("limit", limit),
                (rs, row) -> {
                    Timestamp at = rs.getTimestamp("created_at");
                    return new AdminListingHistoryEntry(
                            rs.getObject("id", UUID.class),
                            rs.getObject("listing_id", UUID.class),
                            rs.getString("title"),
                            ModerationActionType.valueOf(rs.getString("action")),
                            rs.getString("reason_code"),
                            rs.getString("note"),
                            rs.getString("display_name"),
                            at.toInstant());
                });
    }

    /**
     * Whether the account {@code u} is one the seed created. Two statements, because a query
     * naming a table that does not exist fails to plan, whatever branch of it would have run.
     */
    private String isDemo() {
        Boolean seeded = jdbc.queryForObject("SELECT to_regclass('public.demo_seed_ledger') IS NOT NULL",
                new MapSqlParameterSource(), Boolean.class);
        return Boolean.TRUE.equals(seeded)
                ? "EXISTS (SELECT 1 FROM demo_seed_ledger d WHERE d.entity_type = 'USER' AND d.entity_id = u.id)"
                : "FALSE";
    }

    /** So that a search for "50%" or "a_b" means those characters. */
    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
