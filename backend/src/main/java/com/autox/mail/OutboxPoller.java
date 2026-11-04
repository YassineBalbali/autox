package com.autox.mail;

import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends what is in the outbox, every 30 seconds.
 *
 * <p><b>A tick does not always touch the database, on purpose.</b> The free Neon plan covers about
 * 400 hours of compute in a 730-hour month, so the database has to sleep for most of it
 * (docs/deployment.md, "Keeping Neon inside its allowance"), and the uptime monitor keeps this
 * process awake all month. A query every 30 seconds would keep Neon awake too, and spend the
 * month's allowance in about two weeks. So a tick asks the database only when there is reason to:
 * a transaction here committed mail ({@link OutboxSignal}), a retry has come due, or
 * {@code autox.mail.idle-check} has passed, which picks up what another instance left behind.
 *
 * <p>Several instances are safe without {@code ScheduledTaskCoordinator}: {@code SKIP LOCKED} and
 * the claim's lease mean each takes different rows.
 *
 * <p>Nothing here logs a body, a token or an address: a credential's only copies are the outbox
 * row, until it is sent, and the email.
 */
@Component
public class OutboxPoller {

    private static final Logger log = LoggerFactory.getLogger(OutboxPoller.class);

    /** The most emails one poll sends. */
    static final int BATCH_SIZE = 20;

    private final EmailOutbox outbox;
    private final EmailSender sender;
    private final OutboxSignal signal;
    private final MailProperties properties;

    /** Starts in the past, so the first tick after a restart picks up what was left waiting. */
    private volatile Instant nextPollAt = Instant.EPOCH;

    public OutboxPoller(EmailOutbox outbox, EmailSender sender, OutboxSignal signal, MailProperties properties) {
        this.outbox = outbox;
        this.sender = sender;
        this.signal = signal;
        this.properties = properties;
    }

    @Scheduled(cron = "${autox.scheduling.outbox-poll-cron}")
    public void tick() {
        // The signal first and always, so that it is cleared by the poll it causes.
        boolean mailWaiting = signal.takeMailWaiting();
        if (mailWaiting || !Instant.now().isBefore(nextPollAt)) {
            pollNow();
        }
    }

    /** Claims and sends one batch, then works out when the next poll is needed. */
    public int pollNow() {
        List<EmailOutbox.Claimed> claimed = outbox.claimDue(BATCH_SIZE);
        claimed.forEach(this::send);

        Instant idle = Instant.now().plus(properties.idleCheck());
        // A batch that left due rows behind is due now, so the next tick takes them. Asked after
        // sending, so failures that have just been given a retry time count.
        nextPollAt = outbox.nextDueAt().filter(idle::isAfter).orElse(idle);
        return claimed.size();
    }

    private void send(EmailOutbox.Claimed email) {
        try {
            sender.send(new EmailMessage(email.recipient(), email.subject(), email.bodyText(), email.bodyHtml(),
                    email.id().toString()));
        } catch (RuntimeException failure) {
            recordFailure(email, failure);
            return;
        }
        try {
            outbox.markSent(email.id());
            log.info("Email {} ({}) sent on attempt {}", email.id(), email.type(), email.attempts());
        } catch (RuntimeException failure) {
            // It went out and the row does not say so: it will go again when the lease runs out,
            // with the same idempotency key. Not a delivery failure, so not recorded as one.
            log.error("Email {} was sent but could not be marked as sent", email.id(), failure);
        }
    }

    private void recordFailure(EmailOutbox.Claimed email, RuntimeException failure) {
        // A sender's own exception is written to be safe to store. Any other could say anything,
        // so only its type is kept.
        String detail = failure instanceof EmailDeliveryException
                ? failure.getMessage()
                : "Unexpected " + failure.getClass().getSimpleName();
        try {
            boolean last = outbox.recordFailure(email.id(), email.attempts(), detail);
            log.warn("Email {} ({}) failed on attempt {}{}: {}", email.id(), email.type(), email.attempts(),
                    last ? ", the last" : "", detail);
        } catch (RuntimeException unrecorded) {
            // The claim's lease still stands, so it comes due again in a few minutes.
            log.error("Email {} failed, and the failure could not be recorded", email.id(), unrecorded);
        }
    }
}
