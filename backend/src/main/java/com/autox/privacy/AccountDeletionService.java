package com.autox.privacy;

import com.autox.api.error.ApiException;
import com.autox.demo.DemoAccounts;
import com.autox.mail.EmailOutbox;
import com.autox.mail.EmailTemplates;
import com.autox.mail.EmailType;
import com.autox.user.AppUser;
import com.autox.user.AppUserRepository;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes an account at its owner's request.
 *
 * <h2>What goes</h2>
 * The listings with their photos, the saved searches, the sessions and every pending link, the
 * company profile, the anti-abuse counters, the e-mails queued or sent to the address, and every
 * piece of personal data on the account itself.
 *
 * <h2>What stays, without personal data</h2>
 * <ul>
 *   <li><b>The account's row, as a tombstone</b>: status DELETED, named "Compte supprimé", with a
 *       placeholder address no one can receive at and a password no one knows. Conversations,
 *       moderation decisions and the administration log refer to accounts by id, and a reference
 *       to a row that says nothing about anyone keeps them readable without keeping a person.</li>
 *   <li><b>Conversations</b>, for the other party. The deleted person's messages stay in place,
 *       under "Compte supprimé", <b>with their text erased</b>. The text is the deleted person's own
 *       words, and messages are where people put a phone number or an address; the other party
 *       keeps their own side, and the e-mails they were sent. One exception: a message under an
 *       open report keeps its text until a moderator has decided, so deleting an account is not a
 *       way to destroy the evidence against it; the daily sweep erases it after the decision.</li>
 *   <li><b>The administration log</b>, without the addresses it recorded or a suspension's
 *       reason; and <b>moderation decisions</b>, without their note.</li>
 * </ul>
 *
 * <h2>Who cannot</h2>
 * A demo account (the demo seed removes those) and the last administrator able to sign in.
 */
@Service
public class AccountDeletionService {

    private static final Logger log = LoggerFactory.getLogger(AccountDeletionService.class);

    /** The name every deleted account shows. */
    public static final String DELETED_NAME = "Compte supprimé";

    /** {@code .invalid} never resolves: nothing can ever be delivered to a tombstone. */
    static final String TOMBSTONE_DOMAIN = "@deleted.autox.invalid";

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final DemoAccounts demoAccounts;
    private final EmailOutbox outbox;
    private final ListingPurge listings;
    private final NamedParameterJdbcTemplate jdbc;

    public AccountDeletionService(AppUserRepository users, PasswordEncoder passwordEncoder, DemoAccounts demoAccounts,
                                  EmailOutbox outbox, ListingPurge listings, NamedParameterJdbcTemplate jdbc) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.demoAccounts = demoAccounts;
        this.outbox = outbox;
        this.listings = listings;
        this.jdbc = jdbc;
    }

    /**
     * @throws ApiException 400 {@code CURRENT_PASSWORD_INCORRECT}, 409 {@code DEMO_ACCOUNT_CANNOT_BE_DELETED}
     *                      or {@code LAST_ADMIN}, 404 when the account is already gone
     */
    @Transactional
    public void delete(UUID userId, String password) {
        // The administrators' rows and this one, locked: two administrators deleting their accounts
        // at once must not both pass the last-administrator check.
        List<AppUser> locked = users.lockAdministratorsAnd(userId);
        AppUser user = locked.stream()
                .filter(candidate -> candidate.getId().equals(userId))
                .filter(candidate -> candidate.getDeletedAt() == null && candidate.getStatus() != UserStatus.DELETED)
                .findFirst()
                .orElseThrow(() -> ApiException.notFound("User"));

        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw ApiException.currentPasswordIncorrect();
        }
        if (demoAccounts.isDemoAccount(userId)) {
            throw ApiException.demoAccountCannotBeDeleted();
        }
        if (canAdminister(user) && locked.stream().noneMatch(other -> !other.getId().equals(userId) && canAdminister(other))) {
            throw ApiException.lastAdmin();
        }

        Instant now = Instant.now();
        String address = user.getEmail();
        MapSqlParameterSource params = new MapSqlParameterSource("id", userId)
                .addValue("email", address)
                .addValue("now", Timestamp.from(now));

        // The mail first: what was sent to the address goes, and one last e-mail is queued to it.
        jdbc.update("DELETE FROM outbox_email WHERE lower(recipient) = lower(:email)", params);
        outbox.enqueue(new EmailOutbox.Draft(address, EmailType.ACCOUNT_DELETED, EmailTemplates.accountDeleted()));

        ListingPurge.Purged purged = listings.purge(jdbc.queryForList(
                "SELECT id FROM listing WHERE seller_id = :id", params, UUID.class));

        jdbc.update("DELETE FROM saved_search WHERE user_id = :id", params);
        jdbc.update("DELETE FROM refresh_token WHERE user_id = :id", params);
        jdbc.update("DELETE FROM email_verification_token WHERE user_id = :id", params);
        jdbc.update("DELETE FROM password_reset_token WHERE user_id = :id", params);
        // The company's logo goes from storage with the profile, once the deletion is committed.
        List<String> logo = jdbc.queryForList(
                "SELECT logo_storage_key FROM pro_profile WHERE user_id = :id AND logo_storage_key IS NOT NULL",
                params, String.class);
        // Logo uploads never confirmed: the account row stays as a tombstone, so nothing cascades.
        logo.addAll(jdbc.queryForList("DELETE FROM dealer_logo_upload WHERE user_id = :id RETURNING storage_key",
                params, String.class));
        jdbc.update("DELETE FROM pro_profile WHERE user_id = :id", params);
        listings.deleteObjectsAfterCommit(logo);
        jdbc.update("DELETE FROM throttle_counter WHERE scope_key IN (:key, lower(:email))",
                params.addValue("key", userId.toString()));

        // Their reports: on listings, gone with them; on messages, kept for the moderators without
        // the note they wrote.
        jdbc.update("DELETE FROM listing_report WHERE reporter_id = :id", params);
        jdbc.update("UPDATE message_report SET note = NULL WHERE reporter_id = :id", params);

        int erased = jdbc.update("""
                UPDATE message m SET body = NULL, erased_at = :now
                 WHERE m.sender_id = :id AND m.body IS NOT NULL
                   AND NOT EXISTS (SELECT 1 FROM message_report r WHERE r.message_id = m.id AND r.resolution IS NULL)
                """, params);

        jdbc.update("UPDATE admin_user_action SET target_email = NULL, reason = NULL WHERE target_user_id = :id", params);
        jdbc.update("UPDATE admin_user_action SET admin_email = NULL WHERE admin_id = :id", params);

        jdbc.update("""
                UPDATE app_user
                   SET email = :tombstone, password_hash = :hash, display_name = :name,
                       first_name = NULL, last_name = NULL, phone_e164 = NULL, phone_verified_at = NULL,
                       email_verified_at = NULL, email_verification_required = true, last_login_at = NULL,
                       status = 'DELETED', deleted_at = :now, updated_at = :now
                 WHERE id = :id
                """, params.addValue("tombstone", "supprime-" + userId + TOMBSTONE_DOMAIN)
                // A random value nobody is told; one UUID, as BCrypt reads 72 bytes at most.
                .addValue("hash", passwordEncoder.encode(UUID.randomUUID().toString()))
                .addValue("name", DELETED_NAME));

        log.warn("Account {} deleted at its owner's request: {} listing(s), {} stored object(s), {} message(s) erased",
                userId, purged.listings(), purged.objects(), erased);
    }

    private static boolean canAdminister(AppUser user) {
        return user.getRole() == UserRole.ADMIN
                && user.getDeletedAt() == null
                && user.getStatus() != UserStatus.SUSPENDED
                && user.getStatus() != UserStatus.DELETED;
    }
}
