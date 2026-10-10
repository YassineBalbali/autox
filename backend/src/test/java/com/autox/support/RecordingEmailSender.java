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

    @Override
    public void send(EmailMessage message) {
        sent.add(message);
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

    public void clear() {
        sent.clear();
    }

    /** The reset token out of the most recent message's link, as a user clicking it would send. */
    public String lastResetToken() {
        Matcher matcher = TOKEN_IN_LINK.matcher(last().body());
        if (!matcher.find()) {
            throw new AssertionError("No reset token in the email body:\n" + last().body());
        }
        return java.net.URLDecoder.decode(matcher.group(1), java.nio.charset.StandardCharsets.UTF_8);
    }
}
