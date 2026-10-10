package com.autox.mail;

/**
 * One outbound transactional email, as the application describes it.
 *
 * <p>Plain text, deliberately. An HTML template system is a provider decision — most
 * transactional providers want the template to live on their side and take variables over
 * the API — and committing to one here would have to be undone.
 *
 * @param to        recipient address
 * @param subject   subject line
 * @param body      plain-text body
 * @param locale    the recipient's BCP 47 tag, from their account
 * @param messageId an idempotency key the sender may pass to the provider, so a retry
 *                  does not deliver twice
 */
public record EmailMessage(String to, String subject, String body, String locale, String messageId) {

    /**
     * {@code locale} travels with the message even though nothing localises yet. It is the
     * one piece of context a template cannot recover later — the user's language is on
     * their account, and a sender implementation has no business loading accounts — and
     * leaving it out is what would make localisation a refactor instead of an addition.
     */
    public EmailMessage {
        if (locale == null || locale.isBlank()) {
            locale = "en";
        }
    }
}
