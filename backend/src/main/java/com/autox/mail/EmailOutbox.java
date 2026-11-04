package com.autox.mail;

import com.autox.common.UuidV7;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The {@code outbox_email} table: writing mail to send, and what the poller does to it.
 *
 * <p><b>Why a claim is a lease and not a held lock.</b> A row locked for the length of a send
 * would hold a database connection through a call to the provider, up to twenty times over, on a
 * pool of five. Worse, recording a failure from a {@code REQUIRES_NEW} transaction would wait for
 * the lock the outer transaction holds on that same row, and hang. So {@link #claimDue} takes the
 * {@code FOR UPDATE SKIP LOCKED} locks only for the one statement that moves the rows' next
 * attempt forward by {@link #CLAIM_LEASE}, and commits. Another poller then sees them as not due,
 * and an instance that dies mid-send leaves them to be retried when the lease runs out.
 */
@Service
public class EmailOutbox {

    /** The attempts an email gets before it is marked FAILED. */
    static final int MAX_ATTEMPTS = 5;

    /**
     * What to wait after each failed attempt. With {@link #MAX_ATTEMPTS} at five the fifth attempt
     * ends in FAILED, so the last delay here is not reached today; raising the cap to six is what
     * would use it.
     */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5),
            Duration.ofMinutes(15), Duration.ofHours(1), Duration.ofHours(6));

    /** Longer than a full batch can take: twenty sends, each bounded by connect and read timeouts. */
    static final Duration CLAIM_LEASE = Duration.ofMinutes(10);

    private final NamedParameterJdbcTemplate jdbc;
    private final OutboxSignal signal;

    public EmailOutbox(NamedParameterJdbcTemplate jdbc, OutboxSignal signal) {
        this.jdbc = jdbc;
        this.signal = signal;
    }

    /** What a caller wants sent. {@code conversationId} is for {@link EmailType#NEW_MESSAGE} only. */
    public record Draft(String recipient, EmailType type, EmailTemplates.Rendered content, UUID conversationId) {

        public Draft(String recipient, EmailType type, EmailTemplates.Rendered content) {
            this(recipient, type, content, null);
        }
    }

    /** A claimed row. {@code attempts} includes the attempt this claim is for. */
    public record Claimed(UUID id, String recipient, String subject, String bodyText, String bodyHtml,
                          EmailType type, int attempts) {
    }

    /**
     * Adds an email to send. {@code MANDATORY}: the row belongs to the caller's transaction, so it
     * commits with the business change or disappears with it, and a call with no transaction is a
     * programming error rather than mail with nothing behind it.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID enqueue(Draft draft) {
        UUID id = UuidV7.generate();
        jdbc.update("""
                INSERT INTO outbox_email (id, recipient, subject, body_text, body_html, email_type, conversation_id)
                     VALUES (:id, :recipient, :subject, :text, :html, :type, :conversation)
                """, new MapSqlParameterSource("id", id)
                .addValue("recipient", draft.recipient())
                .addValue("subject", draft.content().subject())
                .addValue("text", draft.content().text())
                .addValue("html", draft.content().html())
                .addValue("type", draft.type().name())
                .addValue("conversation", draft.conversationId()));
        // After the commit: told any earlier, the poller could look and see nothing, and then
        // not look again for hours.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                signal.mailWaiting();
            }
        });
        return id;
    }

    /**
     * Whether this person was already notified about this conversation since {@code window} ago.
     *
     * <p>Whatever became of that email, since a failed one was still an attempt to tell them. Two
     * messages at the same moment cannot both pass: the caller has already written its message,
     * which updates the conversation's row, so the second waits for the first to commit and then
     * sees its email.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean notifiedSince(UUID conversationId, String recipient, Duration window) {
        Boolean found = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM outbox_email
                                WHERE conversation_id = :conversation
                                  AND recipient = :recipient
                                  AND created_at > now() - make_interval(secs => :seconds))
                """, new MapSqlParameterSource("conversation", conversationId)
                .addValue("recipient", recipient)
                .addValue("seconds", window.toSeconds()), Boolean.class);
        return Boolean.TRUE.equals(found);
    }

    /**
     * Claims up to {@code limit} due emails for this poller. One statement and one short
     * transaction; see the class comment.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Claimed> claimDue(int limit) {
        // An instance died on the last attempt, so nothing recorded how it ended.
        jdbc.update("""
                UPDATE outbox_email
                   SET status = 'FAILED', body_text = NULL, body_html = NULL,
                       last_error = 'Interrupted during the last attempt'
                 WHERE status = 'PENDING' AND attempts >= :max AND next_attempt_at <= now()
                """, new MapSqlParameterSource("max", MAX_ATTEMPTS));

        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM outbox_email
                     WHERE status = 'PENDING' AND next_attempt_at <= now()
                     ORDER BY next_attempt_at, id
                     LIMIT :limit
                     FOR UPDATE SKIP LOCKED)
                UPDATE outbox_email email
                   SET attempts = email.attempts + 1,
                       next_attempt_at = now() + make_interval(secs => :lease)
                  FROM due
                 WHERE email.id = due.id
             RETURNING email.id, email.recipient, email.subject, email.body_text, email.body_html,
                       email.email_type, email.attempts
                """, new MapSqlParameterSource("limit", limit).addValue("lease", CLAIM_LEASE.toSeconds()),
                (rs, row) -> new Claimed(
                        rs.getObject("id", UUID.class),
                        rs.getString("recipient"),
                        rs.getString("subject"),
                        rs.getString("body_text"),
                        rs.getString("body_html"),
                        EmailType.valueOf(rs.getString("email_type")),
                        rs.getInt("attempts")));
    }

    /** The email went out. Its body goes: it may hold a link that is a credential. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID id) {
        jdbc.update("""
                UPDATE outbox_email
                   SET status = 'SENT', sent_at = now(), last_error = NULL, body_text = NULL, body_html = NULL
                 WHERE id = :id
                """, new MapSqlParameterSource("id", id));
    }

    /**
     * Stores why a send failed, and when to try again or that it will not be. In its own
     * transaction so that it survives whatever the caller's exception does to theirs.
     *
     * @return whether this was the last attempt
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordFailure(UUID id, int attempts, String error) {
        String stored = error.length() <= 500 ? error : error.substring(0, 500);
        if (attempts >= MAX_ATTEMPTS) {
            jdbc.update("""
                    UPDATE outbox_email
                       SET status = 'FAILED', last_error = :error, body_text = NULL, body_html = NULL
                     WHERE id = :id
                    """, new MapSqlParameterSource("id", id).addValue("error", stored));
            return true;
        }
        Duration delay = BACKOFF.get(attempts - 1);
        jdbc.update("""
                UPDATE outbox_email
                   SET last_error = :error, next_attempt_at = now() + make_interval(secs => :delay)
                 WHERE id = :id
                """, new MapSqlParameterSource("id", id).addValue("error", stored)
                .addValue("delay", delay.toSeconds()));
        return false;
    }

    /** When the next email is due, if any is waiting: how long the poller may leave the database alone. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<Instant> nextDueAt() {
        Timestamp next = jdbc.getJdbcTemplate().queryForObject(
                "SELECT min(next_attempt_at) FROM outbox_email WHERE status = 'PENDING'", Timestamp.class);
        return Optional.ofNullable(next).map(Timestamp::toInstant);
    }

    /** Housekeeping: an address is personal data, and the dashboard reads thirty days at most. */
    @Transactional
    public int purgeFinishedBefore(Instant before) {
        return jdbc.update("""
                DELETE FROM outbox_email WHERE status <> 'PENDING' AND created_at < :before
                """, new MapSqlParameterSource("before", Timestamp.from(before)));
    }
}
