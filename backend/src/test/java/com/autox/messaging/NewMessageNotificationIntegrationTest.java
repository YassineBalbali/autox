package com.autox.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.mail.EmailMessage;
import com.autox.mail.OutboxPoller;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.support.RecordingEmailSender;
import com.autox.support.RecordingMail;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Who is told about a message, how often, and what the email does not say. */
@RecordingMail
class NewMessageNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String MESSAGE = "Mon numéro privé est le 06 12 34 56 78, appelez-moi";

    @Autowired RecordingEmailSender emails;
    @Autowired OutboxPoller poller;

    private String sellerEmail;
    private String buyerEmail;
    private JsonNode seller;
    private JsonNode buyer;
    private String listing;

    @BeforeEach
    void setUp() throws Exception {
        emails.clear();
        sellerEmail = uniqueEmail("seller");
        buyerEmail = uniqueEmail("buyer");
        seller = register(sellerEmail, UserRole.PARTICULIER);
        buyer = register(buyerEmail, UserRole.PARTICULIER);
        jdbcTemplate.update("UPDATE app_user SET display_name = 'Camille' WHERE email = ?", buyerEmail);
        jdbcTemplate.update("UPDATE app_user SET display_name = 'Garage Martin' WHERE email = ?", sellerEmail);
        listing = published(seller);
    }

    @Test
    @DisplayName("the recipient is told, never the sender, who wrote, about what, with a link")
    void toTheRecipientOnly() throws Exception {
        String conversation = contact(buyer);

        List<EmailMessage> sent = sendAll();

        assertThat(sent).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo(sellerEmail);
            assertThat(email.subject()).isEqualTo("Nouveau message de Camille sur AutoX");
            for (String body : List.of(email.textBody(), email.htmlBody())) {
                assertThat(body).contains("Camille").contains("https://autox.test/messages/" + conversation);
            }
            assertThat(email.textBody()).contains("BMW 3 Series 320d xDrive M Sport");
        });
    }

    @Test
    @DisplayName("the listing is named as on the result cards, whatever its title starts with")
    void namedAsOnTheResultCards() throws Exception {
        jdbcTemplate.update("""
                UPDATE listing SET title = 'Audi Audi A3 Sportback, très propre', make_text = 'Audi',
                                   model_text = 'A3', version_text = '35 TDI' WHERE id = ?::uuid
                """, listing);
        contact(buyer);

        EmailMessage email = sendAll().getFirst();

        for (String body : List.of(email.textBody(), email.htmlBody())) {
            assertThat(body).contains("à propos de : Audi A3 35 TDI.").doesNotContain("Audi Audi").doesNotContain("très propre");
        }
    }

    @Test
    @DisplayName("a model typed with its make in it does not name the make twice")
    void modelTypedWithItsMake() throws Exception {
        jdbcTemplate.update("""
                UPDATE listing SET make_text = 'Audi', model_text = 'Audi A3', version_text = NULL WHERE id = ?::uuid
                """, listing);
        contact(buyer);

        assertThat(sendAll().getFirst().textBody()).contains("à propos de : Audi A3.").doesNotContain("Audi Audi");
    }

    @Test
    @DisplayName("the email holds neither the message's text nor anyone's address")
    void noTextNoAddress() throws Exception {
        contact(buyer);

        EmailMessage email = sendAll().getFirst();

        for (String content : List.of(email.subject(), email.textBody(), email.htmlBody())) {
            assertThat(content).doesNotContain(MESSAGE).doesNotContain("06 12 34 56 78");
            assertThat(content).doesNotContain(buyerEmail).doesNotContain(sellerEmail).doesNotContain("@");
        }
    }

    @Test
    @DisplayName("a display name or a title that holds an address does not put it in the email")
    void nameThatIsAnAddress() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET display_name = ? WHERE email = ?", buyerEmail, buyerEmail);
        jdbcTemplate.update("UPDATE listing SET version_text = 'Écrivez-moi : vendeur@exemple.fr' WHERE id = ?::uuid", listing);
        contact(buyer);

        EmailMessage email = sendAll().getFirst();

        for (String content : List.of(email.subject(), email.textBody(), email.htmlBody())) {
            assertThat(content).doesNotContain("@");
        }
        assertThat(email.subject()).isEqualTo("Nouveau message de Un utilisateur sur AutoX");
    }

    @Test
    @DisplayName("what other users typed is escaped in the HTML, and cannot add a line to the subject")
    void typedTextIsEscaped() throws Exception {
        jdbcTemplate.update("UPDATE app_user SET display_name = ? WHERE email = ?",
                "<a href=\"https://piege.example\">Camille</a>\nBcc: x", buyerEmail);
        jdbcTemplate.update("UPDATE listing SET model_text = '<img src=x onerror=alert(1)>' WHERE id = ?::uuid", listing);
        contact(buyer);

        EmailMessage email = sendAll().getFirst();

        assertThat(email.htmlBody()).doesNotContain("<a href=\"https://piege.example\"").doesNotContain("<img");
        assertThat(email.htmlBody()).contains("&lt;img");
        assertThat(email.subject()).doesNotContain("\n");
    }

    @Test
    @DisplayName("at most one email per conversation per person in 15 minutes, as the outbox remembers it")
    void throttled() throws Exception {
        String conversation = contact(buyer);
        reply(buyer, conversation);
        reply(buyer, conversation);
        assertThat(outboxRows(sellerEmail)).isOne();

        // Sending it does not reset anything: what counts is when it was written.
        sendAll();
        reply(buyer, conversation);
        assertThat(outboxRows(sellerEmail)).isOne();

        jdbcTemplate.update("UPDATE outbox_email SET created_at = created_at - interval '15 minutes 1 second'");
        reply(buyer, conversation);
        assertThat(outboxRows(sellerEmail)).isEqualTo(2);
    }

    @Test
    @DisplayName("a failed email counts too: the throttle is about attempts to tell them")
    void aFailedEmailCounts() throws Exception {
        String conversation = contact(buyer);
        jdbcTemplate.update("""
                UPDATE outbox_email SET status = 'FAILED', body_text = NULL, body_html = NULL
                """);

        reply(buyer, conversation);

        assertThat(outboxRows(sellerEmail)).isOne();
    }

    @Test
    @DisplayName("a reply reaches the other side at once: one person's throttle does not silence the other")
    void eachSideHasItsOwnThrottle() throws Exception {
        String conversation = contact(buyer);

        reply(seller, conversation);

        assertThat(outboxRows(sellerEmail)).isOne();
        assertThat(outboxRows(buyerEmail)).isOne();
    }

    @Test
    @DisplayName("another conversation is another notification")
    void perConversation() throws Exception {
        contact(buyer);
        JsonNode other = register(uniqueEmail("other"), UserRole.PARTICULIER);

        contact(other);

        assertThat(outboxRows(sellerEmail)).isEqualTo(2);
    }

    @Test
    @DisplayName("a demo seller, whose address is invented, gets no email")
    void demoAccountsGetNoEmail() throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS demo_seed_ledger (
                    entity_type VARCHAR(20) NOT NULL,
                    entity_id   UUID        NOT NULL,
                    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
                    PRIMARY KEY (entity_type, entity_id))""");
        try {
            jdbcTemplate.update("INSERT INTO demo_seed_ledger (entity_type, entity_id) VALUES ('USER', ?)",
                    UUID.fromString(seller.at("/user/id").asText()));

            contact(buyer);

            assertThat(outboxRows(sellerEmail)).isZero();
        } finally {
            jdbcTemplate.execute("DROP TABLE demo_seed_ledger");
        }
    }

    @Test
    @DisplayName("a suspended recipient gets no email")
    void suspendedRecipient() throws Exception {
        String conversation = contact(buyer);
        jdbcTemplate.update("DELETE FROM outbox_email");
        jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE email = ?", buyerEmail);

        // The conversation is closed by then, so the rule is checked where it is applied.
        assertThat(mockMvc.perform(post("/api/v1/conversations/" + conversation + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "Bonjour"))))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        assertThat(outboxRows(buyerEmail)).isZero();
    }

    // ---- helpers --------------------------------------------------------------------------

    private List<EmailMessage> sendAll() {
        poller.pollNow();
        return emails.sent();
    }

    private long outboxRows(String recipient) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_email WHERE recipient = ? AND email_type = 'NEW_MESSAGE'",
                Long.class, recipient);
    }

    private String contact(JsonNode who) throws Exception {
        return json(mockMvc.perform(post("/api/v1/listings/" + listing + "/conversation")
                        .header(HttpHeaders.AUTHORIZATION, bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", MESSAGE))))
                .andExpect(status().isOk())
                .andReturn()).get("id").asText();
    }

    private void reply(JsonNode who, String conversation) throws Exception {
        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(who))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", MESSAGE))))
                .andExpect(status().isOk());
    }

    private String published(JsonNode owner) throws Exception {
        String id = json(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk());
        return id;
    }
}
