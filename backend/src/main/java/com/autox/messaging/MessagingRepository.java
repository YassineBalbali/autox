package com.autox.messaging;

import com.autox.common.UuidV7;
import com.autox.search.ListingSearchRepository;
import com.autox.user.UserRole;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Conversations, messages and reports, in plain SQL.
 *
 * <p>Not JPA: an inbox line is an aggregate — the last message, the unread count, the
 * listing as it is now, the other party — and one query that says so is clearer and cheaper
 * than entities loaded and summed in Java.
 */
@Repository
public class MessagingRepository {

    /** Accounts that may no longer write: closed, or suspended by a moderator. */
    private static final String ACCOUNT_OPEN = "%1$s.status NOT IN ('SUSPENDED', 'DELETED')";

    /**
     * A conversation with its listing and both parties. {@code can_reply} is the rule for
     * writing in an existing conversation: the listing has not been taken down or deleted,
     * and neither account is closed or suspended. A listing that is sold, paused or expired
     * leaves the conversation open — that is when a buyer and a seller have the most to say.
     */
    private static final String HEADER = """
            SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.buyer_read_at, c.seller_read_at,
                   c.last_message_at,
                   l.title, l.price_amount, l.price_currency_code,
                   (%1$s) AS online,
                   (l.status <> 'REMOVED' AND l.deleted_at IS NULL AND %2$s AND %3$s) AS can_reply,
                   b.display_name AS buyer_name, b.role AS buyer_role,
                   s.display_name AS seller_name, s.role AS seller_role
              FROM conversation c
              JOIN listing l ON l.id = c.listing_id
              JOIN app_user b ON b.id = c.buyer_id
              JOIN app_user s ON s.id = c.seller_id
            """.formatted(ListingSearchRepository.visible("l"),
                    ACCOUNT_OPEN.formatted("b"), ACCOUNT_OPEN.formatted("s"));

    private final NamedParameterJdbcTemplate jdbc;

    public MessagingRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- the listing a buyer writes about ---------------------------------------------

    /** The listing's seller and whether it is online, or empty when it does not exist or was deleted. */
    public Optional<ContactTarget> contactTarget(UUID listingId) {
        return jdbc.query("""
                        SELECT l.seller_id, (%s) AS online
                          FROM listing l
                         WHERE l.id = :id AND l.deleted_at IS NULL
                        """.formatted(ListingSearchRepository.visible("l")),
                        new MapSqlParameterSource("id", listingId),
                        rs -> rs.next()
                                ? Optional.of(new ContactTarget(rs.getObject("seller_id", UUID.class), rs.getBoolean("online")))
                                : Optional.empty());
    }

    public Optional<UUID> conversationId(UUID listingId, UUID buyerId) {
        List<UUID> ids = jdbc.queryForList(
                "SELECT id FROM conversation WHERE listing_id = :listing AND buyer_id = :buyer",
                new MapSqlParameterSource("listing", listingId).addValue("buyer", buyerId), UUID.class);
        return ids.stream().findFirst();
    }

    /** Whether an account may write at all. */
    public boolean accountOpen(UUID userId) {
        Boolean open = jdbc.queryForObject(
                "SELECT " + ACCOUNT_OPEN.formatted("u") + " FROM app_user u WHERE u.id = :id",
                new MapSqlParameterSource("id", userId), Boolean.class);
        return Boolean.TRUE.equals(open);
    }

    // ---- writing ----------------------------------------------------------------------

    /**
     * Creates the conversation, or finds the one a concurrent request created a moment
     * earlier: the unique key on (listing, buyer) decides, not a check made before it.
     */
    public UUID createConversation(UUID listingId, UUID buyerId, UUID sellerId, Instant now) {
        jdbc.update("""
                INSERT INTO conversation (id, listing_id, buyer_id, seller_id, created_at, last_message_at)
                     VALUES (:id, :listing, :buyer, :seller, :now, :now)
                ON CONFLICT (listing_id, buyer_id) DO NOTHING
                """, new MapSqlParameterSource("id", UuidV7.generate())
                .addValue("listing", listingId).addValue("buyer", buyerId).addValue("seller", sellerId)
                .addValue("now", Timestamp.from(now)));
        return conversationId(listingId, buyerId).orElseThrow();
    }

    /** Adds a message, and marks the conversation read for its sender up to it. */
    public UUID addMessage(UUID conversationId, UUID senderId, String body, Instant now) {
        UUID id = UuidV7.generate();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("conversation", conversationId).addValue("sender", senderId)
                .addValue("body", body).addValue("now", Timestamp.from(now));
        jdbc.update("""
                INSERT INTO message (id, conversation_id, sender_id, body, created_at)
                     VALUES (:id, :conversation, :sender, :body, :now)
                """, params);
        jdbc.update("""
                UPDATE conversation
                   SET last_message_at = :now,
                       buyer_read_at  = CASE WHEN buyer_id  = :sender THEN :now ELSE buyer_read_at END,
                       seller_read_at = CASE WHEN seller_id = :sender THEN :now ELSE seller_read_at END
                 WHERE id = :conversation
                """, params);
        return id;
    }

    public void markRead(UUID conversationId, UUID userId, Instant now) {
        jdbc.update("""
                UPDATE conversation
                   SET buyer_read_at  = CASE WHEN buyer_id  = :user THEN :now ELSE buyer_read_at END,
                       seller_read_at = CASE WHEN seller_id = :user THEN :now ELSE seller_read_at END
                 WHERE id = :conversation
                """, new MapSqlParameterSource("conversation", conversationId)
                .addValue("user", userId).addValue("now", Timestamp.from(now)));
    }

    // ---- reading ----------------------------------------------------------------------

    public Optional<Header> header(UUID conversationId) {
        return jdbc.query(HEADER + " WHERE c.id = :id", new MapSqlParameterSource("id", conversationId),
                rs -> rs.next() ? Optional.of(header(rs)) : Optional.empty());
    }

    /**
     * The caller's conversations, most recent first, with the last message and the count of
     * messages from the other side they have not opened. A message a moderator hid is not
     * counted as unread: there is nothing in it to read.
     */
    public List<InboxRow> inbox(UUID userId, int limit, long offset) {
        String sql = HEADER.replace(" FROM conversation c", """
                       , lm.body AS last_body, lm.sender_id AS last_sender, lm.hidden_at AS last_hidden,
                       lm.created_at AS last_at,
                       (SELECT count(*) FROM message m
                         WHERE m.conversation_id = c.id
                           AND m.sender_id <> :me
                           AND m.hidden_at IS NULL
                           AND m.created_at > coalesce(
                                 CASE WHEN c.buyer_id = :me THEN c.buyer_read_at ELSE c.seller_read_at END,
                                 '-infinity'::timestamptz)) AS unread
                  FROM conversation c""")
                + """
                 LEFT JOIN LATERAL (
                       SELECT body, sender_id, hidden_at, created_at FROM message
                        WHERE conversation_id = c.id
                        ORDER BY created_at DESC, id DESC LIMIT 1) lm ON true
                 WHERE c.buyer_id = :me OR c.seller_id = :me
                 ORDER BY c.last_message_at DESC, c.id DESC
                 LIMIT :limit OFFSET :offset
                """;
        return jdbc.query(sql, new MapSqlParameterSource("me", userId).addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> new InboxRow(
                        header(rs),
                        rs.getString("last_body"),
                        rs.getObject("last_sender", UUID.class),
                        rs.getTimestamp("last_hidden") != null,
                        instant(rs.getTimestamp("last_at")),
                        rs.getInt("unread")));
    }

    public long countConversations(UUID userId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM conversation WHERE buyer_id = :me OR seller_id = :me",
                new MapSqlParameterSource("me", userId), Long.class);
        return count == null ? 0 : count;
    }

    /** Conversations with at least one message from the other side the caller has not opened. */
    public int unreadConversations(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM conversation c
                 WHERE (c.buyer_id = :me OR c.seller_id = :me)
                   AND EXISTS (
                       SELECT 1 FROM message m
                        WHERE m.conversation_id = c.id
                          AND m.sender_id <> :me
                          AND m.hidden_at IS NULL
                          AND m.created_at > coalesce(
                                CASE WHEN c.buyer_id = :me THEN c.buyer_read_at ELSE c.seller_read_at END,
                                '-infinity'::timestamptz))
                """, new MapSqlParameterSource("me", userId), Integer.class);
        return count == null ? 0 : count;
    }

    /** The latest {@code limit} messages, oldest first, with whether the reader reported each. */
    public List<MessageRow> messages(UUID conversationId, UUID readerId, int limit) {
        List<MessageRow> newestFirst = jdbc.query("""
                SELECT m.id, m.sender_id, m.body, m.hidden_at, m.created_at,
                       EXISTS (SELECT 1 FROM message_report r
                                WHERE r.message_id = m.id AND r.reporter_id = :reader) AS reported
                  FROM message m
                 WHERE m.conversation_id = :conversation
                 ORDER BY m.created_at DESC, m.id DESC
                 LIMIT :limit
                """, new MapSqlParameterSource("conversation", conversationId)
                .addValue("reader", readerId).addValue("limit", limit),
                (rs, row) -> new MessageRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sender_id", UUID.class),
                        rs.getString("body"),
                        rs.getTimestamp("hidden_at") != null,
                        rs.getBoolean("reported"),
                        instant(rs.getTimestamp("created_at"))));
        List<MessageRow> oldestFirst = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    // ---- reports ------------------------------------------------------------------------

    /** The conversation and sender of a message, or empty when there is no such message. */
    public Optional<MessageOwner> messageOwner(UUID messageId) {
        return jdbc.query("SELECT conversation_id, sender_id FROM message WHERE id = :id",
                new MapSqlParameterSource("id", messageId),
                rs -> rs.next()
                        ? Optional.of(new MessageOwner(rs.getObject("conversation_id", UUID.class),
                                rs.getObject("sender_id", UUID.class)))
                        : Optional.empty());
    }

    /** Files a report. A second report of the same message by the same person changes nothing. */
    public void report(UUID messageId, UUID reporterId, MessageReportReason reason, String note, Instant now) {
        jdbc.update("""
                INSERT INTO message_report (id, message_id, reporter_id, reason, note, created_at)
                     VALUES (:id, :message, :reporter, :reason, :note, :now)
                ON CONFLICT (message_id, reporter_id) DO NOTHING
                """, new MapSqlParameterSource("id", UuidV7.generate())
                .addValue("message", messageId).addValue("reporter", reporterId)
                .addValue("reason", reason.name()).addValue("note", note).addValue("now", Timestamp.from(now)));
    }

    /** Open reports, oldest first: the moderators' queue. */
    public List<ReportRow> openReports(int limit, long offset) {
        return jdbc.query("""
                SELECT r.id, r.reason, r.note, r.created_at, r.message_id,
                       m.conversation_id,
                       ru.id AS reporter_id, ru.display_name AS reporter_name, ru.role AS reporter_role,
                       su.id AS sender_id, su.display_name AS sender_name, su.role AS sender_role
                  FROM message_report r
                  JOIN message m ON m.id = r.message_id
                  JOIN app_user ru ON ru.id = r.reporter_id
                  JOIN app_user su ON su.id = m.sender_id
                 WHERE r.resolution IS NULL
                 ORDER BY r.created_at, r.id
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource("limit", limit).addValue("offset", offset),
                (rs, row) -> new ReportRow(
                        rs.getObject("id", UUID.class),
                        MessageReportReason.valueOf(rs.getString("reason")),
                        rs.getString("note"),
                        instant(rs.getTimestamp("created_at")),
                        rs.getObject("message_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        new PartyRow(rs.getObject("reporter_id", UUID.class), rs.getString("reporter_name"),
                                UserRole.valueOf(rs.getString("reporter_role"))),
                        new PartyRow(rs.getObject("sender_id", UUID.class), rs.getString("sender_name"),
                                UserRole.valueOf(rs.getString("sender_role")))));
    }

    public long countOpenReports() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM message_report WHERE resolution IS NULL",
                new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    /**
     * The reported message and up to {@code around} messages either side of it, oldest first.
     * A moderator sees the hidden ones' text too: they are the ones who need it.
     */
    public List<ContextRow> context(UUID conversationId, UUID messageId, int around) {
        return jdbc.query("""
                WITH numbered AS (
                    SELECT m.id, m.sender_id, m.body, m.hidden_at, m.created_at,
                           row_number() OVER (ORDER BY m.created_at, m.id) AS n
                      FROM message m
                     WHERE m.conversation_id = :conversation),
                     target AS (SELECT n FROM numbered WHERE id = :message)
                SELECT numbered.* FROM numbered, target
                 WHERE numbered.n BETWEEN target.n - :around AND target.n + :around
                 ORDER BY numbered.n
                """, new MapSqlParameterSource("conversation", conversationId)
                .addValue("message", messageId).addValue("around", around),
                (rs, row) -> new ContextRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("sender_id", UUID.class),
                        rs.getString("body"),
                        rs.getTimestamp("hidden_at") != null,
                        instant(rs.getTimestamp("created_at"))));
    }

    /** The message a report is about, and whether the report is still open. */
    public Optional<ReportTarget> reportTarget(UUID reportId) {
        return jdbc.query("SELECT message_id, resolution IS NULL AS open FROM message_report WHERE id = :id",
                new MapSqlParameterSource("id", reportId),
                rs -> rs.next()
                        ? Optional.of(new ReportTarget(rs.getObject("message_id", UUID.class), rs.getBoolean("open")))
                        : Optional.empty());
    }

    /** Hides a message from both sides. Its text is kept. Hiding a hidden message changes nothing. */
    public void hide(UUID messageId, UUID moderatorId, Instant now) {
        jdbc.update("""
                UPDATE message SET hidden_at = :now, hidden_by = :moderator
                 WHERE id = :message AND hidden_at IS NULL
                """, new MapSqlParameterSource("message", messageId)
                .addValue("moderator", moderatorId).addValue("now", Timestamp.from(now)));
    }

    /** Closes every open report on a message, the way the moderator decided. */
    public void resolveReports(UUID messageId, MessageReportResolution resolution, UUID moderatorId, Instant now) {
        jdbc.update("""
                UPDATE message_report
                   SET resolution = :resolution, resolved_at = :now, resolved_by = :moderator
                 WHERE message_id = :message AND resolution IS NULL
                """, new MapSqlParameterSource("message", messageId).addValue("resolution", resolution.name())
                .addValue("moderator", moderatorId).addValue("now", Timestamp.from(now)));
    }

    /** A row selected by {@link #HEADER}. */
    private static Header header(ResultSet rs) throws SQLException {
        return new Header(
                rs.getObject("id", UUID.class),
                rs.getObject("listing_id", UUID.class),
                rs.getObject("buyer_id", UUID.class),
                rs.getObject("seller_id", UUID.class),
                instant(rs.getTimestamp("buyer_read_at")),
                instant(rs.getTimestamp("seller_read_at")),
                instant(rs.getTimestamp("last_message_at")),
                rs.getString("title"),
                rs.getLong("price_amount"),
                rs.getString("price_currency_code"),
                rs.getBoolean("online"),
                rs.getBoolean("can_reply"),
                new PartyRow(rs.getObject("buyer_id", UUID.class), rs.getString("buyer_name"),
                        UserRole.valueOf(rs.getString("buyer_role"))),
                new PartyRow(rs.getObject("seller_id", UUID.class), rs.getString("seller_name"),
                        UserRole.valueOf(rs.getString("seller_role"))));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    // ---- rows ---------------------------------------------------------------------------

    public record ContactTarget(UUID sellerId, boolean online) {
    }

    public record PartyRow(UUID id, String displayName, UserRole role) {
    }

    public record Header(UUID id, UUID listingId, UUID buyerId, UUID sellerId, Instant buyerReadAt,
                         Instant sellerReadAt, Instant lastMessageAt, String title, long priceAmount,
                         String currencyCode, boolean online, boolean canReply, PartyRow buyer, PartyRow seller) {

        public boolean hasParticipant(UUID userId) {
            return buyerId.equals(userId) || sellerId.equals(userId);
        }
    }

    public record InboxRow(Header header, String lastBody, UUID lastSenderId, boolean lastHidden,
                           Instant lastSentAt, int unread) {
    }

    public record MessageRow(UUID id, UUID senderId, String body, boolean hidden, boolean reportedByReader,
                             Instant sentAt) {
    }

    public record MessageOwner(UUID conversationId, UUID senderId) {
    }

    public record ReportRow(UUID id, MessageReportReason reason, String note, Instant reportedAt, UUID messageId,
                            UUID conversationId, PartyRow reporter, PartyRow sender) {
    }

    public record ContextRow(UUID id, UUID senderId, String body, boolean hidden, Instant sentAt) {
    }

    public record ReportTarget(UUID messageId, boolean open) {
    }
}
