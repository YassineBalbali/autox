package com.autox.mail;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transactional email configuration, bound from {@code autox.mail.*}.
 *
 * @param provider      which {@link EmailSender} to use: {@code log}, the local default, or
 *                      {@code brevo}. Selected by an explicit value rather than by which beans
 *                      exist.
 * @param apiKey        the provider's key. Required at startup when the provider is {@code brevo}
 *                      (see {@code RequiredSetting}), like the two addresses below.
 * @param fromAddress   envelope sender; for Brevo, a sender verified in its console.
 * @param fromName      display name shown to the recipient.
 * @param baseUrl       the provider's API root. Configurable so a test can point the real client
 *                      at a local server, not so production can move it.
 * @param timeout       how long one send may take to connect, and to answer.
 * @param idleCheck     how often the poller looks at the database when it knows of no mail. See
 *                      {@link OutboxPoller}.
 * @param siteUrl       the web app's origin, which the verification and new-message emails link
 *                      to. The API and the web app are deployed separately, so it cannot guess.
 * @param passwordResetUrl the frontend page that takes a reset token, which the email links
 *                      to with {@code ?token=…}.
 */
@ConfigurationProperties(prefix = "autox.mail")
public record MailProperties(
        String provider,
        String apiKey,
        String fromAddress,
        String fromName,
        String baseUrl,
        Duration timeout,
        Duration idleCheck,
        String siteUrl,
        String passwordResetUrl) {

    public MailProperties {
        provider = orDefault(provider, "log");
        fromAddress = orDefault(fromAddress, "no-reply@autox.local");
        fromName = orDefault(fromName, "AutoX");
        baseUrl = orDefault(baseUrl, "https://api.brevo.com/v3");
        timeout = positiveOr(timeout, Duration.ofSeconds(10));
        idleCheck = positiveOr(idleCheck, Duration.ofHours(6));
        siteUrl = orDefault(siteUrl, "http://localhost:4200").replaceAll("/+$", "");
        passwordResetUrl = orDefault(passwordResetUrl, "http://localhost:4200/reset-password");
    }

    /** Spelled out so that printing this record cannot print the key. */
    @Override
    public String toString() {
        return "MailProperties[provider=" + provider + ", fromName=" + fromName + "]";
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
