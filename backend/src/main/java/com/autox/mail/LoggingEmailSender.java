package com.autox.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes email to the log instead of sending it.
 *
 * <p>This is how the reset flow is usable in development: the token is in the body, and
 * the body is in the console, so the whole journey can be walked without a mail provider.
 *
 * <p>It is also a loaded gun, and says so. The body of a password reset email <em>is</em> a
 * credential: anyone who can read the log can take over any account. So it warns once at
 * startup, loudly enough that nobody deploys it by accident.
 *
 * <p>Selection is by the explicit {@code autox.mail.provider} property rather than by
 * "is there another bean". A real provider is a {@link EmailSender} bean conditional on its
 * own value of that property, which means switching to it is a configuration change and the
 * two are never both in play — whereas a missing-bean condition in application code depends
 * on which configuration class Spring happens to read first.
 */
@Component
@ConditionalOnProperty(name = "autox.mail.provider", havingValue = "log", matchIfMissing = true)
public class LoggingEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    public LoggingEmailSender() {
        log.warn("No email provider is configured (autox.mail.provider=log): transactional email is "
                + "written to this log instead of being sent, so password reset links are readable "
                + "here. Do not run this configuration in production.");
    }

    @Override
    public void send(EmailMessage message) {
        log.info("""

                        ---- email (not sent: no provider configured) ----
                        to:      {}
                        locale:  {}
                        subject: {}
                        id:      {}

                        {}
                        --------------------------------------------------""",
                message.to(), message.locale(), message.subject(), message.messageId(), message.body());
    }
}
