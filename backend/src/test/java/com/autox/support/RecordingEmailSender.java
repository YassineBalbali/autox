package com.autox.support;

import com.autox.mail.EmailMessage;
import com.autox.mail.EmailSender;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures outbound email so a test can read what a user would have received.
 *
 * <p>This is the only way to test the reset flow end to end: the database stores a hash of
 * the token, so the plaintext exists in exactly one place — the message. A test that read
 * the token out of the database instead would be asserting against an implementation detail
 * and would pass even if the email were never composed.
 */
public class RecordingEmailSender implements EmailSender {

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("[?&]token=([^\\s&]+)");

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private volatile RuntimeException failure;

    @Override
    public void send(EmailMessage message) {
        if (failure != null) {
            throw failure;
        }
        sent.add(message);
    }

    /** From now on, every send throws this. */
    public void failWith(RuntimeException failure) {
        this.failure = failure;
    }

    /** Back to delivering. */
    public void deliver() {
        this.failure = null;
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public EmailMessage last() {
        if (sent.isEmpty()) {
            throw new AssertionError("No email was sent");
        }
        return sent.getLast();
    }

    /**
     * Empties the mailbox and goes back to delivering. Both, because one sender is shared by
     * every class that records mail ({@link RecordingMail}): a failure one test left set would
     * otherwise reach the next class.
     */
    public void clear() {
        sent.clear();
        failure = null;
    }

    /** The reset token out of the most recent message's link, as a user clicking it would send. */
    public String lastResetToken() {
        Matcher matcher = TOKEN_IN_LINK.matcher(last().textBody());
        if (!matcher.find()) {
            throw new AssertionError("No reset token in the email body:\n" + last().textBody());
        }
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }
}
