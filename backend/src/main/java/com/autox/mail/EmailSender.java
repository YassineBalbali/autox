package com.autox.mail;

/**
 * Sends transactional email. One method, so a real provider is one class and one bean.
 *
 * <p>No provider is configured yet; {@link LoggingEmailSender} stands in for development.
 * The interface exists so that the gap is a missing implementation rather than a missing
 * feature: the password reset flow is complete and tested, and the only thing between it
 * and production is a bean that puts the message on the wire.
 *
 * <p><b>Contract.</b> An implementation must not throw on a delivery failure it cannot do
 * anything about. Callers treat sending as best-effort: a reset request answers the same
 * way whether or not the mail got out, because telling the caller otherwise would say
 * whether the address has an account.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
