package com.autox.mail;

import java.util.regex.Pattern;
import org.springframework.web.util.HtmlUtils;

/**
 * What the emails say: French, as plain text and as simple HTML.
 *
 * <p>A listing's title and a person's display name are typed by other users, so in HTML they are
 * escaped, and in a subject they lose their line breaks.
 */
public final class EmailTemplates {

    private static final Pattern EMAIL_ADDRESS = Pattern.compile("[^\\s@]+@[^\\s@]+");

    /** The last line of every email. */
    static final String NO_REPLY = "Cet e-mail est envoyé automatiquement, merci de ne pas y répondre.";

    static final String REPLY_ON_AUTOX = "Pour répondre, rendez-vous dans la conversation sur AutoX.";

    private EmailTemplates() {
    }

    /** One email's content. */
    public record Rendered(String subject, String text, String html) {
    }

    public static Rendered passwordReset(String link, long validMinutes) {
        return linkEmail(
                "Réinitialisez votre mot de passe AutoX",
                "Quelqu’un a demandé à réinitialiser le mot de passe de votre compte AutoX.",
                "Pour en choisir un nouveau, ouvrez ce lien dans les " + validMinutes + " minutes :",
                "Choisir un nouveau mot de passe",
                link,
                "Le lien ne fonctionne qu’une fois. Si vous n’êtes pas à l’origine de cette demande, ignorez "
                        + "cet e-mail : votre mot de passe n’a pas changé.");
    }

    public static Rendered emailVerification(String link, long validHours) {
        return linkEmail(
                "Confirmez votre adresse e-mail AutoX",
                "Bienvenue sur AutoX. Il reste à confirmer que cette adresse est bien la vôtre.",
                "Ouvrez ce lien dans les " + validHours + " heures :",
                "Confirmer mon adresse",
                link,
                "Le lien ne fonctionne qu’une fois. Si vous n’avez pas créé de compte AutoX, ignorez cet e-mail.");
    }

    /**
     * Who wrote, about what, and a link. Never the message's text: it stays on the site, where it
     * can be reported and hidden.
     */
    public static Rendered newMessage(String senderName, String listingTitle, String conversationLink) {
        String sender = withoutAddress(senderName, "Un utilisateur");
        String listing = withoutAddress(listingTitle, "une annonce");
        String sentence = sender + " vous a écrit à propos de : " + listing + ".";
        return new Rendered(
                oneLine("Nouveau message de " + sender + " sur AutoX"),
                sentence + "\n\nLire le message et y répondre :\n\n" + conversationLink + "\n\n"
                        + REPLY_ON_AUTOX + "\n\n" + NO_REPLY,
                page(escape(sentence),
                        button("Lire le message et y répondre", conversationLink),
                        escape(REPLY_ON_AUTOX)));
    }

    private static Rendered linkEmail(String subject, String intro, String instruction, String action, String link,
                                      String closing) {
        return new Rendered(
                subject,
                intro + "\n\n" + instruction + "\n\n" + link + "\n\n" + closing + "\n\n" + NO_REPLY,
                page(escape(intro) + "</p><p style=\"margin:0 0 16px\">" + escape(instruction),
                        button(action, link) + "<p style=\"margin:0 0 16px;font-size:13px;word-break:break-all\">"
                                + escape(link) + "</p>",
                        escape(closing)));
    }

    private static String page(String intro, String action, String closing) {
        return """
                <!doctype html>
                <html lang="fr"><body style="margin:0;padding:24px;font-family:Arial,sans-serif;font-size:15px;line-height:1.5;color:#1a1a1a">
                <div style="max-width:520px;margin:0 auto">
                <p style="margin:0 0 16px;font-size:18px;font-weight:bold">AutoX</p>
                <p style="margin:0 0 16px">%s</p>
                %s
                <p style="margin:0 0 16px;font-size:13px;color:#555555">%s</p>
                <p style="margin:0;font-size:13px;color:#555555">%s</p>
                </div></body></html>""".formatted(intro, action, closing, escape(NO_REPLY));
    }

    private static String button(String label, String link) {
        return "<p style=\"margin:0 0 16px\"><a href=\"" + escape(link) + "\" style=\"display:inline-block;"
                + "padding:10px 16px;background:#1a56db;color:#ffffff;text-decoration:none;border-radius:4px\">"
                + escape(label) + "</a></p>";
    }

    /** A display name or a title that holds an address would put it in the email. */
    private static String withoutAddress(String value, String fallback) {
        String flat = value == null ? "" : oneLine(value);
        return flat.isEmpty() || EMAIL_ADDRESS.matcher(flat).find() ? fallback : flat;
    }

    private static String oneLine(String value) {
        return value.replaceAll("\\s+", " ").strip();
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value, "UTF-8");
    }
}
