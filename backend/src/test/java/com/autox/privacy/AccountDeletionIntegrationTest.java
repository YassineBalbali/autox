package com.autox.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** "Supprimer mon compte": what goes, what stays without personal data, and who cannot. */
class AccountDeletionIntegrationTest extends PrivacyTestSupport {

    @Autowired DataRetentionSweeper retention;

    private String sellerEmail;
    private JsonNode seller;
    private JsonNode buyer;
    private String listing;

    @BeforeEach
    void setUp() throws Exception {
        sellerEmail = uniqueEmail("leaving");
        seller = register(sellerEmail, UserRole.PARTICULIER);
        buyer = register(uniqueEmail("staying"), UserRole.PARTICULIER);
        listing = publishedListing(seller);
    }

    @Test
    @DisplayName("the wrong password deletes nothing")
    void wrongPassword() throws Exception {
        deleteAccount(seller, "not-the-password")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM app_user WHERE id = ?::uuid", String.class,
                userId(seller))).isNotEqualTo("DELETED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isOne();
    }

    @Test
    @DisplayName("needs a session")
    void anonymous() throws Exception {
        mockMvc.perform(post("/api/v1/me/deletion").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the listings go with their photos in storage, and the saved searches, sessions and tokens")
    void deletesWhatIsTheirs() throws Exception {
        List<String> keys = storedKeys(listing);
        assertThat(keys).hasSize(2).allSatisfy(key -> assertThat(stored(key)).isTrue());
        saveSearch(seller);
        String id = userId(seller);

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing WHERE seller_id = ?::uuid", Integer.class, id)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing_image", Integer.class)).isZero();
        assertThat(keys).allSatisfy(key -> assertThat(stored(key)).as(key).isFalse());
        for (String table : List.of("saved_search", "refresh_token", "email_verification_token", "password_reset_token",
                "pro_profile")) {
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?::uuid",
                    Integer.class, id)).as(table).isZero();
        }
    }

    @Test
    @DisplayName("the account keeps no personal data: no address, name, phone or usable password; it cannot sign in")
    void tombstone() throws Exception {
        String id = userId(seller);
        jdbcTemplate.update("UPDATE app_user SET first_name = 'Jeanne', last_name = 'Martin', phone_e164 = '+33612345678' "
                + "WHERE id = ?::uuid", id);

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> row = jdbcTemplate.queryForMap("SELECT * FROM app_user WHERE id = ?::uuid", id);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("deleted_at")).isNotNull();
        assertThat(row.get("display_name")).isEqualTo("Compte supprimé");
        assertThat((String) row.get("email")).doesNotContain("leaving").endsWith("@deleted.autox.invalid");
        assertThat(row.get("first_name")).isNull();
        assertThat(row.get("last_name")).isNull();
        assertThat(row.get("phone_e164")).isNull();
        assertThat(row.get("last_login_at")).isNull();

        mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", sellerEmail, "password", PASSWORD))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", seller.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());
        // The address is free again.
        register(sellerEmail, UserRole.PARTICULIER);
    }

    @Test
    @DisplayName("the access token still in hand is refused at once, everywhere, not 15 minutes later")
    void accessTokenRevokedAtOnce() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(seller))).andExpect(status().isOk());
        JsonNode admin = admin();
        // A second administrator, so that the first one may leave.
        admin();

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());
        deleteAccount(admin, PASSWORD).andExpect(status().isNoContent());

        for (String path : List.of("/api/v1/me", "/api/v1/conversations", "/api/v1/me/export")) {
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                    .andExpect(status().isUnauthorized());
        }
        // A public page too: a token that is sent is checked, as an expired one would be.
        mockMvc.perform(get("/api/v1/listings").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isUnauthorized());
        // The one who stays is not concerned.
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(buyer))).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a token whose account row is gone altogether is refused too")
    void accountRowGone() throws Exception {
        JsonNode gone = register(uniqueEmail("gone"), UserRole.PARTICULIER);
        for (String table : List.of("refresh_token", "email_verification_token")) {
            jdbcTemplate.update("DELETE FROM " + table + " WHERE user_id = ?::uuid", userId(gone));
        }
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?::uuid", userId(gone));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(gone)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the other party keeps the conversation: the deleted person's messages stay under « Compte supprimé », without their text")
    void conversationsStay() throws Exception {
        String conversation = contact(buyer, listing, "Bonjour, toujours disponible ?");
        reply(seller, conversation, "Oui, appelez-moi au 06 12 34 56 78.");

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());

        JsonNode seen = conversation(buyer, conversation);
        assertThat(seen.path("listing").isMissingNode()).as("the listing went with the account").isTrue();
        assertThat(seen.at("/counterpart/displayName").asText()).isEqualTo("Compte supprimé");
        assertThat(seen.get("canReply").asBoolean()).isFalse();
        JsonNode messages = seen.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).get("body").asText()).isEqualTo("Bonjour, toujours disponible ?");
        assertThat(messages.get(0).get("erased").asBoolean()).isFalse();
        assertThat(messages.get(1).path("body").isMissingNode()).isTrue();
        assertThat(messages.get(1).get("erased").asBoolean()).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message WHERE body LIKE '%06 12%'", Integer.class)).isZero();

        JsonNode inbox = json(mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                .andExpect(status().isOk()).andReturn());
        assertThat(inbox.at("/items/0/lastMessage/erased").asBoolean()).isTrue();
        assertThat(inbox.at("/items/0/lastMessage/preview").isMissingNode()).isTrue();
    }

    @Test
    @DisplayName("a message under an open report keeps its text until a moderator has decided, then the daily sweep erases it")
    void reportedMessageKeptForTheModerator() throws Exception {
        String conversation = contact(buyer, listing, "Bonjour");
        reply(seller, conversation, "Payez d’abord par mandat cash.");
        String message = conversation(buyer, conversation).at("/messages/1/id").asText();
        mockMvc.perform(post("/api/v1/messages/" + message + "/report").header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SCAM\"}"))
                .andExpect(status().isNoContent());

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());
        assertThat(bodyOf(message)).isEqualTo("Payez d’abord par mandat cash.");

        JsonNode admin = admin();
        String report = jdbcTemplate.queryForObject("SELECT id::text FROM message_report", String.class);
        mockMvc.perform(post("/api/v1/admin/message-reports/" + report + "/dismiss")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        retention.apply(Instant.now());

        assertThat(bodyOf(message)).isNull();
    }

    @Test
    @DisplayName("the moderation history and the administration log are kept, without personal data")
    void recordsAnonymised() throws Exception {
        String id = userId(seller);
        JsonNode admin = admin();
        UUID adminId = UUID.fromString(userId(admin));
        jdbcTemplate.update("""
                INSERT INTO moderation_action (id, listing_id, admin_id, action, reason_code, note, created_at)
                VALUES (?, ?::uuid, ?, 'REJECT', 'MISLEADING_INFORMATION', 'Le vendeur, Jeanne Martin, ment.', now())""",
                UUID.randomUUID(), listing, adminId);
        jdbcTemplate.update("""
                INSERT INTO admin_user_action (id, admin_id, admin_email, target_user_id, target_email, action, reason,
                                               previous_value, new_value, created_at)
                VALUES (?, ?, 'admin@autox.test', ?::uuid, ?, 'SUSPEND', 'Arnaque signalée par Paul.', 'ACTIVE', 'SUSPENDED', now())""",
                UUID.randomUUID(), adminId, id, sellerEmail);

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());

        Map<String, Object> decision = jdbcTemplate.queryForMap("SELECT * FROM moderation_action");
        assertThat(decision.get("listing_id")).isNull();
        assertThat(decision.get("note")).isNull();
        assertThat(decision.get("reason_code")).isEqualTo("MISLEADING_INFORMATION");
        Map<String, Object> action = jdbcTemplate.queryForMap("SELECT * FROM admin_user_action");
        assertThat(action.get("target_user_id").toString()).isEqualTo(id);
        assertThat(action.get("target_email")).isNull();
        assertThat(action.get("reason")).isNull();
        assertThat(action.get("action")).isEqualTo("SUSPEND");
    }

    @Test
    @DisplayName("a confirmation goes to the address through the outbox, and what was sent to it before is gone")
    void confirmationEmail() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO outbox_email (id, recipient, subject, body_text, body_html, email_type, status, attempts,
                                          next_attempt_at, created_at, sent_at)
                VALUES (?, ?, 'Ancien', 'x', 'x', 'NEW_MESSAGE', 'SENT', 1, now(), now(), now())""",
                UUID.randomUUID(), sellerEmail);

        deleteAccount(seller, PASSWORD).andExpect(status().isNoContent());

        List<Map<String, Object>> mail = jdbcTemplate.queryForList(
                "SELECT recipient, email_type, status, subject FROM outbox_email");
        assertThat(mail).hasSize(1);
        assertThat(mail.getFirst()).containsEntry("recipient", sellerEmail)
                .containsEntry("email_type", "ACCOUNT_DELETED").containsEntry("status", "PENDING");
        assertThat((String) mail.getFirst().get("subject")).contains("supprimé");
    }

    @Test
    @DisplayName("the last administrator able to sign in cannot delete their account; with another, they can")
    void lastAdministrator() throws Exception {
        JsonNode admin = admin();

        deleteAccount(admin, PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LAST_ADMIN"));

        admin();
        deleteAccount(admin, PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("a demo account cannot be deleted this way")
    void demoAccount() throws Exception {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS demo_seed_ledger (entity_type VARCHAR(20) NOT NULL, entity_id UUID NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), PRIMARY KEY (entity_type, entity_id))""");
        try {
            jdbcTemplate.update("INSERT INTO demo_seed_ledger (entity_type, entity_id) VALUES ('USER', ?::uuid)", userId(seller));

            deleteAccount(seller, PASSWORD)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("DEMO_ACCOUNT_CANNOT_BE_DELETED"));
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM listing", Integer.class)).isOne();
        } finally {
            jdbcTemplate.execute("DROP TABLE demo_seed_ledger");
        }
    }

    private String bodyOf(String messageId) {
        return jdbcTemplate.queryForObject("SELECT body FROM message WHERE id = ?::uuid", String.class, messageId);
    }
}
