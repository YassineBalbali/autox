package com.autox.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.autox.auth.verification.EmailVerificationService;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.RecordingEmailSender;
import com.autox.support.RecordingMail;
import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox, against a real PostgreSQL with real commits: the row goes with the transaction, and
 * the poller's claim, backoff, cap and failure handling.
 */
@RecordingMail
@ExtendWith(OutputCaptureExtension.class)
class OutboxIntegrationTest extends AbstractIntegrationTest {

    private static final String SECRET = "le-jeton-secret-0123456789";

    @Autowired RecordingEmailSender emails;
    @Autowired EmailOutbox outbox;
    @Autowired OutboxPoller poller;
    @Autowired OutboxSignal signal;
    @Autowired MailProperties mail;
    @Autowired EmailVerificationService verification;
    @Autowired PlatformTransactionManager transactions;

    private TransactionTemplate inTransaction;

    @BeforeEach
    void setUp() {
        emails.clear();
        emails.deliver();
        signal.takeMailWaiting();
        inTransaction = new TransactionTemplate(transactions);
    }

    // ---- with the business change ---------------------------------------------------------

    @Test
    @DisplayName("the email commits with the business change")
    void committedWithTheChange() throws Exception {
        String email = uniqueEmail("committed");

        registerUnverified(email, UserRole.PARTICULIER);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM app_user WHERE email = ?", Long.class, email))
                .isOne();
        assertThat(row(email)).containsEntry("email_type", "EMAIL_VERIFICATION").containsEntry("status", "PENDING");
        assertThat(emails.sent()).as("nothing is sent inside the request").isEmpty();
    }

    @Test
    @DisplayName("a rollback takes the email with it, and the token")
    void absentOnRollback() {
        AppUser user = createUser(uniqueEmail("rolled-back"), UserRole.PARTICULIER, UserStatus.PENDING_VERIFICATION);

        inTransaction.executeWithoutResult(status -> {
            verification.issue(user);
            assertThat(count()).isOne();
            status.setRollbackOnly();
        });

        assertThat(count()).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM email_verification_token", Long.class)).isZero();
        assertThat(signal.takeMailWaiting()).as("no reason to wake the poller").isFalse();
    }

    @Test
    @DisplayName("an email cannot be written outside a transaction")
    void needsATransaction() {
        assertThatThrownBy(() -> enqueue("nobody@autox.test"))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    // ---- the claim ------------------------------------------------------------------------

    @Test
    @DisplayName("only due rows are claimed, at most a batch")
    void claimsOnlyDueRows() {
        UUID notYet = insert("later@autox.test");
        jdbcTemplate.update("UPDATE outbox_email SET next_attempt_at = now() + interval '1 minute' WHERE id = ?", notYet);
        List<UUID> due = new ArrayList<>();
        for (int i = 0; i < OutboxPoller.BATCH_SIZE + 3; i++) {
            due.add(insert("due-" + i + "@autox.test"));
        }

        List<EmailOutbox.Claimed> claimed = outbox.claimDue(OutboxPoller.BATCH_SIZE);

        assertThat(claimed).hasSize(OutboxPoller.BATCH_SIZE);
        assertThat(claimed).extracting(EmailOutbox.Claimed::id).doesNotContain(notYet).isSubsetOf(due);
        assertThat(claimed).allSatisfy(email -> assertThat(email.attempts()).isOne());
        // Claimed rows are leased: claiming again takes only what is left.
        assertThat(outbox.claimDue(OutboxPoller.BATCH_SIZE)).hasSize(3);
    }

    @Test
    @Timeout(30)
    @DisplayName("a row another transaction has locked is skipped, without waiting for it")
    void skipsLockedRows() {
        UUID locked = insert("locked@autox.test");
        UUID free = insert("free@autox.test");

        inTransaction.executeWithoutResult(status -> {
            jdbcTemplate.queryForList("SELECT id FROM outbox_email WHERE id = ? FOR UPDATE", locked);

            // claimDue is REQUIRES_NEW: a second connection, as a second poller would be.
            assertThat(outbox.claimDue(10)).extracting(EmailOutbox.Claimed::id).containsExactly(free);
        });

        assertThat(outbox.claimDue(10)).extracting(EmailOutbox.Claimed::id).containsExactly(locked);
    }

    @Test
    @Timeout(60)
    @DisplayName("two pollers at once send every email exactly once")
    void twoPollersSendEachOnce() throws Exception {
        int total = 3 * OutboxPoller.BATCH_SIZE;
        for (int i = 0; i < total; i++) {
            insert("many-" + i + "@autox.test");
        }

        OutboxPoller first = new OutboxPoller(outbox, emails, signal, mail);
        OutboxPoller second = new OutboxPoller(outbox, emails, signal, mail);
        CountDownLatch start = new CountDownLatch(1);
        Thread a = new Thread(() -> drain(first, start));
        Thread b = new Thread(() -> drain(second, start));
        a.start();
        b.start();
        start.countDown();
        a.join();
        b.join();

        assertThat(emails.sent()).extracting(EmailMessage::to).hasSize(total).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_email WHERE status = 'SENT' AND attempts = 1", Long.class))
                .isEqualTo(total);
    }

    private void drain(OutboxPoller poller, CountDownLatch start) {
        try {
            start.await();
            while (poller.pollNow() > 0) {
                Thread.onSpinWait();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- sending and failing --------------------------------------------------------------

    @Test
    @DisplayName("a sent email is marked SENT and keeps no body, since a body can hold a link")
    void sentClearsTheBody() {
        UUID id = insert("sent@autox.test");

        assertThat(poller.pollNow()).isOne();

        assertThat(emails.last().to()).isEqualTo("sent@autox.test");
        assertThat(emails.last().htmlBody()).contains(SECRET);
        assertThat(emails.last().messageId()).isEqualTo(id.toString());
        Map<String, Object> row = row("sent@autox.test");
        assertThat(row).containsEntry("status", "SENT");
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("body_text")).isNull();
        assertThat(row.get("body_html")).isNull();
    }

    @Test
    @DisplayName("the failure status survives the exception, and the poll carries on with the next email")
    void failureSurvivesTheException() {
        insert("fails@autox.test");
        emails.failWith(new EmailDeliveryException("HTTP 503 unavailable: try later"));

        assertThat(poller.pollNow()).isOne();

        Map<String, Object> row = row("fails@autox.test");
        assertThat(row).containsEntry("status", "PENDING").containsEntry("last_error", "HTTP 503 unavailable: try later");
        assertThat(((Number) row.get("attempts")).intValue()).isOne();
        assertThat(row.get("body_text")).as("it has to be sent again").isNotNull();

        emails.deliver();
        jdbcTemplate.update("UPDATE outbox_email SET next_attempt_at = now()");
        poller.pollNow();
        assertThat(row("fails@autox.test")).containsEntry("status", "SENT").containsEntry("last_error", null);
    }

    @Test
    @DisplayName("backs off 1, 5, 15 and 60 minutes, and the fifth failure is FAILED, with no body and no more attempts")
    void backsOffAndStopsAtTheCap() {
        insert("always-fails@autox.test");
        emails.failWith(new EmailDeliveryException("HTTP 500"));

        for (long minutes : List.of(1L, 5L, 15L, 60L)) {
            poller.pollNow();

            Map<String, Object> row = row("always-fails@autox.test");
            assertThat(row).containsEntry("status", "PENDING");
            assertThat(nextAttempt()).isCloseTo(Instant.now().plus(Duration.ofMinutes(minutes)), within(30, ChronoUnit.SECONDS));
            assertThat(poller.pollNow()).as("not due yet").isZero();
            jdbcTemplate.update("UPDATE outbox_email SET next_attempt_at = now()");
        }

        poller.pollNow();

        Map<String, Object> row = row("always-fails@autox.test");
        assertThat(row).containsEntry("status", "FAILED").containsEntry("last_error", "HTTP 500");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(EmailOutbox.MAX_ATTEMPTS);
        assertThat(row.get("body_text")).isNull();
        assertThat(row.get("body_html")).isNull();

        jdbcTemplate.update("UPDATE outbox_email SET next_attempt_at = now()");
        assertThat(poller.pollNow()).isZero();
    }

    @Test
    @DisplayName("an instance that dies on the last attempt leaves a row that ends as FAILED")
    void diedOnTheLastAttempt() {
        UUID id = insert("orphan@autox.test");
        jdbcTemplate.update("UPDATE outbox_email SET attempts = ? WHERE id = ?", EmailOutbox.MAX_ATTEMPTS, id);

        assertThat(poller.pollNow()).isZero();

        assertThat(row("orphan@autox.test")).containsEntry("status", "FAILED");
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    @DisplayName("neither the row nor the log keeps an unexpected exception's message, or a body")
    void nothingSecretIsStoredOrLogged(CapturedOutput output) {
        insert("leaky@autox.test");
        emails.failWith(new IllegalStateException("could not send " + SECRET));

        poller.pollNow();

        assertThat(row("leaky@autox.test")).containsEntry("last_error", "Unexpected IllegalStateException");
        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("leaky@autox.test");

        emails.deliver();
        jdbcTemplate.update("UPDATE outbox_email SET next_attempt_at = now()");
        poller.pollNow();
        assertThat(output.getAll()).doesNotContain(SECRET).doesNotContain("leaky@autox.test");
    }

    // ---- leaving the database alone -------------------------------------------------------

    @Test
    @DisplayName("an idle tick does not look: only committed mail, a retry or the idle check make it")
    void idleTicksLeaveTheDatabaseAlone() {
        poller.pollNow();
        // In the table, but nothing here committed it: as if another instance had.
        insert("elsewhere@autox.test");
        signal.takeMailWaiting();

        poller.tick();
        assertThat(emails.sent()).isEmpty();

        inTransaction.executeWithoutResult(status -> enqueue("here@autox.test"));
        poller.tick();

        assertThat(emails.sent()).extracting(EmailMessage::to)
                .containsExactlyInAnyOrder("elsewhere@autox.test", "here@autox.test");
    }

    @Test
    @DisplayName("after a failure the next look is when the retry is due")
    void aRetryIsWhenItLooksNext() {
        insert("retry@autox.test");
        emails.failWith(new EmailDeliveryException("HTTP 500"));

        poller.pollNow();

        Instant next = (Instant) ReflectionTestUtils.getField(poller, "nextPollAt");
        assertThat(next).isCloseTo(nextAttempt(), within(1, ChronoUnit.MILLIS));
        assertThat(next).isBefore(Instant.now().plus(Duration.ofMinutes(2)));
    }

    @Test
    @DisplayName("with nothing waiting, the next look is the idle check")
    void nothingWaiting() {
        poller.pollNow();

        Instant next = (Instant) ReflectionTestUtils.getField(poller, "nextPollAt");
        assertThat(next).isCloseTo(Instant.now().plus(mail.idleCheck()), within(1, ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("a new poller looks at once, so a restart picks up what was waiting")
    void aRestartLooks() {
        insert("before-restart@autox.test");
        signal.takeMailWaiting();

        new OutboxPoller(outbox, emails, signal, mail).tick();

        assertThat(emails.sent()).extracting(EmailMessage::to).containsExactly("before-restart@autox.test");
    }

    // ---- helpers --------------------------------------------------------------------------

    private void enqueue(String recipient) {
        outbox.enqueue(new EmailOutbox.Draft(recipient, EmailType.NEW_MESSAGE,
                new EmailTemplates.Rendered("Objet", "Texte " + SECRET, "<p>HTML " + SECRET + "</p>")));
    }

    /** A committed row, written without telling the poller. */
    private UUID insert(String recipient) {
        UUID id = com.autox.common.UuidV7.generate();
        jdbcTemplate.update("""
                INSERT INTO outbox_email (id, recipient, subject, body_text, body_html, email_type)
                VALUES (?, ?, 'Objet', ?, ?, 'NEW_MESSAGE')
                """, id, recipient, "Texte " + SECRET, "<p>HTML " + SECRET + "</p>");
        return id;
    }

    private long count() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_email", Long.class);
    }

    private Map<String, Object> row(String recipient) {
        return jdbcTemplate.queryForMap("SELECT * FROM outbox_email WHERE recipient = ?", recipient);
    }

    private Instant nextAttempt() {
        return jdbcTemplate.queryForObject("SELECT next_attempt_at FROM outbox_email", java.sql.Timestamp.class)
                .toInstant();
    }
}
