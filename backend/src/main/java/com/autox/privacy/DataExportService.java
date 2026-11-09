package com.autox.privacy;

import com.autox.api.error.ApiException;
import com.autox.storage.ObjectStorage;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A copy of what AutoX holds about an account, as one JSON document: the account and company,
 * the listings with their photos and price history, the conversations, the saved searches and the
 * sessions.
 *
 * <p>Everything is the account's own, except in conversations, where the other party appears by
 * display name only, and a message a moderator hid shows as hidden, without its text, when the
 * other party wrote it. The photos are links, valid for a few hours, rather than bytes.
 *
 * <p>Rate-limited ({@link #PER_WINDOW} an hour): each one reads everything the account has.
 */
@Service
public class DataExportService {

    static final int PER_WINDOW = 3;
    static final Duration WINDOW = Duration.ofHours(1);

    private final NamedParameterJdbcTemplate jdbc;
    private final ThrottleCounters counters;
    private final ObjectStorage storage;
    private final ObjectMapper json;

    public DataExportService(NamedParameterJdbcTemplate jdbc, ThrottleCounters counters, ObjectStorage storage,
                             ObjectMapper json) {
        this.jdbc = jdbc;
        this.counters = counters;
        this.storage = storage;
        this.json = json;
    }

    /** Counts one export and refuses past the limit. Its own transaction, so a refusal is counted too. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void allow(UUID userId) {
        Instant now = Instant.now();
        ThrottleCounters.WindowCount count = counters.recordInFixedWindow(
                ThrottleScope.DATA_EXPORT, userId.toString(), now, WINDOW);
        if (count.eventCount() > PER_WINDOW) {
            throw ApiException.rateLimited(Math.max(1, Duration.between(now, count.windowEndsAt()).toSeconds() + 1));
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> export(UUID userId) {
        MapSqlParameterSource me = new MapSqlParameterSource("me", userId);
        List<Map<String, Object>> accounts = rows("""
                SELECT u.id, u.email, u.display_name, u.first_name, u.last_name, u.phone_e164 AS phone, u.role,
                       u.status, u.country_code, u.locale, u.preferred_currency_code, u.created_at,
                       u.email_verified_at, u.last_login_at
                  FROM app_user u WHERE u.id = :me AND u.deleted_at IS NULL""", me);
        if (accounts.isEmpty()) {
            throw ApiException.notFound("User");
        }
        Map<String, Object> account = accounts.getFirst();
        rows("""
                SELECT company_name, legal_form, registration_number, vat_number, website_url, description,
                       address_line1, address_line2, contact_phone_e164 AS contact_phone, opening_hours,
                       show_phone, slug AS public_page, hidden_at, hidden_reason, created_at, updated_at
                  FROM pro_profile WHERE user_id = :me""", me).stream().findFirst()
                .ifPresent(company -> account.put("company", company));

        Map<String, Object> export = new LinkedHashMap<>();
        export.put("exportedAt", Instant.now());
        export.put("account", account);
        export.put("listings", listings(me));
        export.put("conversations", conversations(userId, me));
        List<Map<String, Object>> searches = rows("""
                SELECT name, criteria::text AS criteria, link_params::text AS search_parameters, created_at, paused_at,
                       last_digest_at,
                       last_visited_at
                  FROM saved_search WHERE user_id = :me ORDER BY created_at""", me);
        for (Map<String, Object> search : searches) {
            search.replace("criteria", parsed((String) search.get("criteria")));
            search.replace("searchParameters", parsed((String) search.get("searchParameters")));
        }
        export.put("savedSearches", searches);
        export.put("sessions", rows("""
                SELECT device_label, user_agent, ip_address, issued_at, last_used_at, expires_at, revoked_at
                  FROM refresh_token WHERE user_id = :me ORDER BY issued_at""", me));
        return export;
    }

    private List<Map<String, Object>> listings(MapSqlParameterSource me) {
        List<Map<String, Object>> listings = rows("""
                SELECT l.*, mk.name AS make_name, mo.name AS model_name
                  FROM listing l
                  LEFT JOIN vehicle_make mk ON mk.id = l.make_id
                  LEFT JOIN vehicle_model mo ON mo.id = l.model_id
                 WHERE l.seller_id = :me ORDER BY l.created_at""", me);
        for (Map<String, Object> listing : listings) {
            listing.remove("sellerId");
            listing.remove("version");
            MapSqlParameterSource id = new MapSqlParameterSource("id", listing.get("id"));
            List<Map<String, Object>> photos = rows("""
                    SELECT position, storage_key, original_filename, content_type, width, height, byte_size, uploaded_at
                      FROM listing_image WHERE listing_id = :id AND status = 'READY' ORDER BY position""", id);
            for (Map<String, Object> photo : photos) {
                photo.put("url", storage.readUrl((String) photo.remove("storageKey")));
            }
            listing.put("photos", photos);
            listing.put("priceHistory", rows("""
                    SELECT price_amount, price_currency_code, reason, created_at
                      FROM listing_price_history WHERE listing_id = :id ORDER BY created_at""", id));
        }
        return listings;
    }

    private List<Map<String, Object>> conversations(UUID userId, MapSqlParameterSource me) {
        List<Map<String, Object>> conversations = rows("""
                SELECT c.id, CASE WHEN c.buyer_id = :me THEN 'BUYER' ELSE 'SELLER' END AS role,
                       l.title AS listing_title, other.display_name AS counterpart, c.created_at, c.last_message_at
                  FROM conversation c
                  LEFT JOIN listing l ON l.id = c.listing_id
                  JOIN app_user other ON other.id = CASE WHEN c.buyer_id = :me THEN c.seller_id ELSE c.buyer_id END
                 WHERE c.buyer_id = :me OR c.seller_id = :me
                 ORDER BY c.last_message_at""", me);
        for (Map<String, Object> conversation : conversations) {
            conversation.put("messages", rows("""
                    SELECT m.sender_id = :me AS sent_by_me,
                           CASE WHEN m.sender_id = :me OR m.hidden_at IS NULL THEN m.body END AS text,
                           m.hidden_at IS NOT NULL AS hidden, m.created_at AS sent_at
                      FROM message m WHERE m.conversation_id = :id ORDER BY m.created_at, m.id""",
                    new MapSqlParameterSource("id", conversation.get("id")).addValue("me", userId)));
        }
        return conversations;
    }

    /** Rows with camelCase keys and plain JSON values: instants, JSON documents, lists. */
    private List<Map<String, Object>> rows(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, row) -> {
            Map<String, Object> values = new LinkedHashMap<>();
            for (int column = 1; column <= rs.getMetaData().getColumnCount(); column++) {
                values.put(camel(rs.getMetaData().getColumnLabel(column)), plain(rs.getObject(column)));
            }
            return values;
        });
    }

    private Object plain(Object value) throws SQLException {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        // The driver's own types (an address, an interval): their text form.
        if (value != null && value.getClass().getName().startsWith("org.postgresql")) {
            return value.toString();
        }
        if (value instanceof Array array) {
            List<Object> items = new ArrayList<>();
            for (Object item : (Object[]) array.getArray()) {
                items.add(item);
            }
            return items;
        }
        return value;
    }

    private Object parsed(String document) {
        if (document == null) {
            return null;
        }
        try {
            return json.readTree(document);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return document;
        }
    }

    private static String camel(String column) {
        StringBuilder name = new StringBuilder();
        boolean upper = false;
        for (char c : column.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                name.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return name.toString();
    }
}
