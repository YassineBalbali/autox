package com.autox.assistant;

import java.util.regex.Pattern;

/**
 * Removes e-mail addresses and phone numbers from a message before it is sent to the provider
 * (docs/assistant-plan.md, 3.3). The disclosure asks users not to type any; this is for when they
 * do. The answer then says the message held contact details that were removed.
 *
 * <p>Also from what a seller typed that an answer shows, a listing's title and city
 * ({@link #strip}): moderation removes a number placed there, after a report, and the assistant
 * does not carry one in the meantime. The seller is reached through the listing's own button.
 */
public final class ContactMask {

    static final String REPLACEMENT = "[coordonnées retirées]";

    private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}-]+(\\.[\\p{L}\\p{N}-]+)+");
    /**
     * French numbers (06 12 34 56 78, +33 6…, 0033 6…) and international ones written with a +.
     * Not any long run of digits: "10 000 2019" is a price and a year.
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?:(?:\\+|00)\\s?33\\s?(?:\\(0\\)\\s?)?|\\b0)[1-9](?:[\\s.-]?\\d{2}){4}\\b|\\+\\d{1,3}(?:[\\s.-]?\\d){7,}");

    private ContactMask() {
    }

    /** A seller's text with each address and number replaced; null stays null. */
    public static String strip(String text) {
        return text == null ? null : mask(text).text();
    }

    /** @return the message with each address and number replaced, and whether there was one */
    static Masked mask(String message) {
        String masked = PHONE.matcher(EMAIL.matcher(message).replaceAll(REPLACEMENT)).replaceAll(REPLACEMENT);
        return new Masked(masked, !masked.equals(message));
    }

    record Masked(String text, boolean removed) {
    }
}
