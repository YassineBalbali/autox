package com.autox.privacy;

import com.autox.scheduling.ScheduledTaskCoordinator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * How long deleted listings, conversations and the administration log are kept, applied once a day. The privacy page
 * states these rules; a change here is a change there.
 *
 * <ul>
 *   <li><b>A listing its seller deleted</b> is hidden at once, and erased with its photos
 *       {@link #DELETED_LISTINGS} later: time to undo a mistake on request, and for a moderator to
 *       look into a report about it.</li>
 *   <li><b>A conversation</b>, with its messages, is erased {@link #CONVERSATIONS} after its last
 *       message: a sale is long over by then. Not while one of its messages is under an open
 *       report: the moderator decides first.</li>
 *   <li><b>The text of a deleted account's messages</b> is erased once no open report holds it
 *       back ({@link AccountDeletionService} erases the rest at the deletion itself).</li>
 *   <li><b>The administration log</b> (admin_user_action: suspensions, reactivations, role changes)
 *       is deleted {@link #ADMIN_ACTIONS} after each action: long enough to answer for a decision,
 *       not kept for ever. Moderation decisions on listings are not concerned: they carry no
 *       personal data and belong to the listing's history.</li>
 * </ul>
 */
@Component
public class DataRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionSweeper.class);

    static final Duration DELETED_LISTINGS = Duration.ofDays(30);
    static final Duration CONVERSATIONS = Duration.ofDays(365);
    /** Matches the bound in the trigger on admin_user_action (V27). */
    static final Duration ADMIN_ACTIONS = Duration.ofDays(365);

    private final NamedParameterJdbcTemplate jdbc;
    private final ListingPurge listings;
    private final ScheduledTaskCoordinator coordinator;
    private final TransactionTemplate transactions;

    public DataRetentionSweeper(NamedParameterJdbcTemplate jdbc, ListingPurge listings,
                                ScheduledTaskCoordinator coordinator, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.listings = listings;
        this.coordinator = coordinator;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Daily, at night, on one instance at a time. */
    @Scheduled(cron = "${autox.scheduling.data-retention-cron}", zone = "Europe/Paris")
    public void sweep() {
        coordinator.runExclusively("data-retention", () -> apply(Instant.now()));
    }

    /** What one run erased. */
    public record Swept(int listings, int objects, int conversations, int messagesErased, int adminActions) {
    }

    /** One run, as of {@code now}, in one transaction. Called by {@link #sweep()}, and by tests with a clock of their own. */
    public Swept apply(Instant now) {
        return transactions.execute(status -> applyInTransaction(now));
    }

    private Swept applyInTransaction(Instant now) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("listingsBefore", Timestamp.from(now.minus(DELETED_LISTINGS)))
                .addValue("conversationsBefore", Timestamp.from(now.minus(CONVERSATIONS)))
                .addValue("adminActionsBefore", Timestamp.from(now.minus(ADMIN_ACTIONS)))
                .addValue("now", Timestamp.from(now));

        ListingPurge.Purged purged = listings.purge(jdbc.queryForList(
                "SELECT id FROM listing WHERE deleted_at < :listingsBefore", params, UUID.class));

        int conversations = jdbc.update("""
                DELETE FROM conversation c
                 WHERE c.last_message_at < :conversationsBefore
                   AND NOT EXISTS (SELECT 1 FROM message m JOIN message_report r ON r.message_id = m.id
                                    WHERE m.conversation_id = c.id AND r.resolution IS NULL)
                """, params);

        int erased = jdbc.update("""
                UPDATE message m SET body = NULL, erased_at = :now
                  FROM app_user u
                 WHERE u.id = m.sender_id AND u.status = 'DELETED' AND m.body IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM message_report r WHERE r.message_id = m.id AND r.resolution IS NULL)
                """, params);

        // The trigger on admin_user_action refuses a row younger than a year, whatever asks.
        int adminActions = jdbc.update("DELETE FROM admin_user_action WHERE created_at < :adminActionsBefore", params);

        Swept swept = new Swept(purged.listings(), purged.objects(), conversations, erased, adminActions);
        if (swept.listings() + swept.conversations() + swept.messagesErased() + swept.adminActions() > 0) {
            log.info("Retention: erased {} deleted listing(s) ({} stored object(s)), {} conversation(s), "
                    + "the text of {} message(s) from deleted accounts, {} administration log entr(ies)",
                    swept.listings(), swept.objects(), swept.conversations(), swept.messagesErased(),
                    swept.adminActions());
        }
        return swept;
    }
}
