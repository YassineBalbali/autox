package com.autox.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transactional email configuration, bound from {@code autox.mail.*}.
 *
 * @param provider      which {@link EmailSender} to use. {@code log} is the only one that
 *                      exists; a real provider adds a value and a bean conditional on it.
 * @param fromAddress   envelope sender. Unused by the logging sender, and required by every
 *                      real one, so it is configured now rather than discovered later.
 * @param fromName      display name shown to the recipient.
 * @param passwordResetUrl the frontend page that takes a reset token, which the email links
 *                      to with {@code ?token=…}. The backend cannot guess this: the API and
 *                      the web app are separately deployed, and a native app may want a
 *                      deep link instead.
 */
@ConfigurationProperties(prefix = "autox.mail")
public record MailProperties(String provider, String fromAddress, String fromName, String passwordResetUrl) {

    public MailProperties {
        provider = orDefault(provider, "log");
        fromAddress = orDefault(fromAddress, "no-reply@autox.local");
        fromName = orDefault(fromName, "AutoX");
        passwordResetUrl = orDefault(passwordResetUrl, "http://localhost:4200/reset-password");
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
