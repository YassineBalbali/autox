package com.autox.mail;

import java.util.ArrayList;
import java.util.List;
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
     * The last e-mail to an address: its account is gone. Sent to the address as it was, which is
     * erased from the account in the same transaction; the outbox keeps it until sent, then for
     * its usual retention.
     */
    public static Rendered accountDeleted() {
        String intro = "Votre compte AutoX a été supprimé, à votre demande.";
        String details = "Votre profil, vos annonces et leurs photos, vos recherches enregistrées et vos sessions ont "
                + "été effacés. Dans les conversations, vos messages apparaissent désormais sous « Compte supprimé », "
                + "sans leur texte.";
        String closing = "La suppression a été confirmée avec votre mot de passe. Si vous n’en êtes pas à l’origine, "
                + "quelqu’un le connaît : changez-le partout où vous l’utilisez.";
        return new Rendered(
                "Votre compte AutoX a été supprimé",
                intro + "\n\n" + details + "\n\n" + closing + "\n\n" + NO_REPLY,
                page(escape(intro), "<p style=\"margin:0 0 16px\">" + escape(details) + "</p>", escape(closing)));
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

    /** One listing in a digest. {@code leased}: it states a monthly payment. */
    public record DigestItem(String label, String facts, String link, boolean leased) {
    }

    /**
     * One saved search in a digest.
     *
     * @param total how many are new, of which {@code items} holds the first few
     */
    public record DigestSection(String name, int total, List<DigestItem> items, String seeAllLink,
                                String unsubscribeLink) {
    }

    /** Monthly payments are advertising for credit, which has to carry this: docs/leasing.md. */
    static final String CREDIT_MENTION =
            "Un crédit vous engage et doit être remboursé. Vérifiez vos capacités de remboursement avant de vous engager.";

    /** Every search with something new, one e-mail. A search's name and a listing's label are typed by users. */
    public static Rendered savedSearchDigest(List<DigestSection> sections, String mySearchesLink) {
        int total = sections.stream().mapToInt(DigestSection::total).sum();
        boolean anyLeased = sections.stream().flatMap(section -> section.items().stream()).anyMatch(DigestItem::leased);

        List<String> text = new ArrayList<>();
        StringBuilder html = new StringBuilder();
        for (DigestSection section : sections) {
            String name = oneLine(section.name());
            String heading = name + " : " + countOf(section.total());
            StringBuilder block = new StringBuilder(heading).append('\n');
            html.append("<h2 style=\"margin:24px 0 8px;font-size:16px\">").append(escape(heading)).append("</h2>\n");
            html.append("<ul style=\"margin:0 0 12px;padding:0;list-style:none\">\n");
            for (DigestItem item : section.items()) {
                String label = withoutAddress(item.label(), "Annonce");
                block.append("\n- ").append(label).append('\n').append("  ").append(item.facts()).append('\n')
                        .append("  ").append(item.link()).append('\n');
                html.append("<li style=\"margin:0 0 10px\"><a href=\"").append(escape(item.link())).append("\">")
                        .append(escape(label)).append("</a><br><span style=\"font-size:13px;color:#555555\">")
                        .append(escape(item.facts())).append("</span></li>\n");
            }
            block.append("\nVoir toutes les annonces : ").append(section.seeAllLink())
                    .append("\nNe plus recevoir cette alerte : ").append(section.unsubscribeLink());
            text.add(block.toString());
            html.append("</ul>\n<p style=\"margin:0 0 4px\"><a href=\"").append(escape(section.seeAllLink()))
                    .append("\">Voir toutes les annonces</a></p>\n")
                    .append("<p style=\"margin:0 0 16px;font-size:13px\"><a href=\"")
                    .append(escape(section.unsubscribeLink())).append("\">Ne plus recevoir cette alerte</a></p>\n");
        }

        String manage = "Gérer vos alertes dans Mes recherches";
        String mention = anyLeased ? CREDIT_MENTION + "\n\n" : "";
        return new Rendered(
                countOf(total) + " pour vos recherches AutoX",
                String.join("\n\n", text) + "\n\n" + mention + manage + " : " + mySearchesLink + "\n\n" + NO_REPLY,
                page(escape(countOf(total)) + " pour vos recherches.",
                        html.toString()
                                + (anyLeased ? "<p style=\"margin:0 0 16px;font-weight:bold\">" + escape(CREDIT_MENTION) + "</p>\n" : "")
                                + "<p style=\"margin:0 0 16px\"><a href=\"" + escape(mySearchesLink) + "\">" + escape(manage) + "</a></p>",
                        escape("Cet e-mail résume les nouvelles annonces de vos recherches enregistrées, une fois par jour.")));
    }

    private static String countOf(int count) {
        return count + (count > 1 ? " nouvelles annonces" : " nouvelle annonce");
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
