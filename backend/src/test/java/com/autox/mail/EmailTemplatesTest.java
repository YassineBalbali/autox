package com.autox.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** What every email ends with, and what a notification says about replying. */
class EmailTemplatesTest {

    private static final String NO_REPLY = "Cet e-mail est envoyé automatiquement, merci de ne pas y répondre.";

    private static final EmailTemplates.Rendered RESET =
            EmailTemplates.passwordReset("https://autox.test/reset?token=t", 30);
    private static final EmailTemplates.Rendered VERIFICATION =
            EmailTemplates.emailVerification("https://autox.test/verifier-email?token=t", 24);
    private static final EmailTemplates.Rendered NOTIFICATION =
            EmailTemplates.newMessage("Camille", "Peugeot 208", "https://autox.test/messages/c");

    @Test
    @DisplayName("every email ends with the no-reply line, as text and as HTML")
    void everyEmailEndsWithTheLine() {
        for (EmailTemplates.Rendered email : List.of(RESET, VERIFICATION, NOTIFICATION)) {
            assertThat(email.text()).endsWith(NO_REPLY);
            assertThat(email.html()).endsWith(NO_REPLY + "</p>\n</div></body></html>");
        }
    }

    @Test
    @DisplayName("a notification also says to reply from the conversation, before the last line")
    void notificationSaysWhereToReply() {
        String reply = "Pour répondre, rendez-vous dans la conversation sur AutoX.";

        for (String body : List.of(NOTIFICATION.text(), NOTIFICATION.html())) {
            assertThat(body).contains(reply);
            assertThat(body.indexOf(reply)).isLessThan(body.indexOf(NO_REPLY));
        }
        assertThat(RESET.text()).doesNotContain(reply);
        assertThat(VERIFICATION.text()).doesNotContain(reply);
    }
}
