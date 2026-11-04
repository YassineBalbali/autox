package com.autox.mail;

/**
 * Puts one email on the wire. One method, so a provider is one class and one bean.
 *
 * <p>Only {@link OutboxPoller} calls this. Anything that wants to send mail writes to
 * {@link EmailOutbox} in its own transaction instead, so no request waits on a provider.
 *
 * <p><b>Contract.</b> A delivery failure is thrown as {@link EmailDeliveryException}: the poller
 * records it and tries again later, and returning normally marks the email sent.
 */
public interface EmailSender {

    void send(EmailMessage message);
}
