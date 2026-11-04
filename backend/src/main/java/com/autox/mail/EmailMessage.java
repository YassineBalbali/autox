package com.autox.mail;

/**
 * One outbound transactional email, as a sender receives it.
 *
 * @param to        recipient address
 * @param subject   subject line
 * @param textBody  plain-text body
 * @param htmlBody  the same content as simple HTML
 * @param messageId the outbox row's id: the same on every attempt, so the sender can pass it to
 *                  the provider as an idempotency key
 */
public record EmailMessage(String to, String subject, String textBody, String htmlBody, String messageId) {
}
