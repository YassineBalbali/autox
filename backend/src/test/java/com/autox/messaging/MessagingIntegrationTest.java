package com.autox.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Messages between a buyer and a seller, against a real PostgreSQL.
 *
 * <p>Each test has a seller with one published listing, and a buyer. What is checked is
 * the contract the feature exists for: a buyer reaches a seller only through here, nobody
 * else can read the conversation, nothing sent can be changed, and a recipient can report
 * what they received.
 */
class MessagingIntegrationTest extends AbstractIntegrationTest {

    private JsonNode seller;
    private JsonNode buyer;
    private String sellerEmail;
    private String listingId;

    @BeforeEach
    void setUp() throws Exception {
        sellerEmail = uniqueEmail("msg-seller");
        seller = register(sellerEmail, UserRole.PARTICULIER);
        buyer = register(uniqueEmail("msg-buyer"), UserRole.PARTICULIER);
        listingId = publishedListing(seller);
    }

    @Nested
    @DisplayName("a buyer contacts a seller")
    class Contact {

        @Test
        @DisplayName("starts a conversation about the listing, as the buyer")
        void startsAConversation() throws Exception {
            contact(buyer, "Bonjour, est-elle toujours disponible ?")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("BUYER"))
                    .andExpect(jsonPath("$.listing.id").value(listingId))
                    .andExpect(jsonPath("$.listing.online").value(true))
                    .andExpect(jsonPath("$.counterpart.displayName").value("Test PARTICULIER"))
                    .andExpect(jsonPath("$.canReply").value(true))
                    .andExpect(jsonPath("$.messages.length()").value(1))
                    .andExpect(jsonPath("$.messages[0].body").value("Bonjour, est-elle toujours disponible ?"))
                    .andExpect(jsonPath("$.messages[0].sentByMe").value(true));
        }

        @Test
        @DisplayName("never shows the seller's email address, to the buyer or in the inbox")
        void noContactDetails() throws Exception {
            String detail = contact(buyer, "Bonjour").andReturn().getResponse().getContentAsString();
            String inbox = mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, bearer(buyer)))
                    .andReturn().getResponse().getContentAsString();

            assertThat(detail).doesNotContain(sellerEmail).doesNotContain("email").doesNotContain("phone");
            assertThat(inbox).doesNotContain(sellerEmail).doesNotContain("email").doesNotContain("phone");
        }

        @Test
        @DisplayName("contacting the seller again continues the same conversation")
        void oneConversationPerListingAndBuyer() throws Exception {
            String first = id(contact(buyer, "Bonjour"));
            String second = id(contact(buyer, "Et le contrôle technique ?"));

            assertThat(second).isEqualTo(first);
            conversation(buyer, first).andExpect(jsonPath("$.messages.length()").value(2));
        }

        @Test
        @DisplayName("a seller cannot write to themselves about their own listing")
        void notOwnListing() throws Exception {
            contact(seller, "Moi-même").andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("a listing that is not online cannot be written about for the first time")
        void onlyOnline() throws Exception {
            act(seller, "/api/v1/listings/" + listingId + "/unpublish");

            contact(buyer, "Bonjour").andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("an empty or overlong message is refused")
        void validation() throws Exception {
            contact(buyer, "   \n  ").andExpect(status().isBadRequest());
            contact(buyer, "x".repeat(2001)).andExpect(status().isBadRequest());
            contact(buyer, "x".repeat(2000)).andExpect(status().isOk());
        }

        @Test
        @DisplayName("anonymous visitors must sign in first")
        void requiresAnAccount() throws Exception {
            mockMvc.perform(post("/api/v1/listings/" + listingId + "/conversation")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"body\":\"Bonjour\"}"))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("a suspended account cannot write, even with a token issued before the suspension")
        void suspendedCannotWrite() throws Exception {
            jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE id = ?::uuid", userId(buyer));

            contact(buyer, "Bonjour")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
        }
    }

    @Nested
    @DisplayName("both sides see the conversation, and nobody else does")
    class Inboxes {

        @Test
        @DisplayName("the seller has it in their inbox, unread, as the seller")
        void sellerInbox() throws Exception {
            contact(buyer, "Bonjour, est-elle toujours disponible ?");

            mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].role").value("SELLER"))
                    .andExpect(jsonPath("$.items[0].unreadCount").value(1))
                    .andExpect(jsonPath("$.items[0].lastMessage.preview").value("Bonjour, est-elle toujours disponible ?"))
                    .andExpect(jsonPath("$.items[0].lastMessage.sentByMe").value(false));
            unread(seller).andExpect(jsonPath("$.conversations").value(1));
            // The buyer's own message is not unread for them.
            unread(buyer).andExpect(jsonPath("$.conversations").value(0));
        }

        @Test
        @DisplayName("opening it does not mark it read; marking it read does")
        void markRead() throws Exception {
            String id = id(contact(buyer, "Bonjour"));

            conversation(seller, id).andExpect(status().isOk());
            unread(seller).andExpect(jsonPath("$.conversations").value(1));

            act(seller, "/api/v1/conversations/" + id + "/read");
            unread(seller).andExpect(jsonPath("$.conversations").value(0));
        }

        @Test
        @DisplayName("a reply from the seller is unread for the buyer, and read for the seller")
        void sellerReplies() throws Exception {
            String id = id(contact(buyer, "Bonjour"));

            reply(seller, id, "Oui, toujours disponible.")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value("SELLER"))
                    .andExpect(jsonPath("$.messages[1].sentByMe").value(true));

            unread(buyer).andExpect(jsonPath("$.conversations").value(1));
            // Replying is reading: the seller has seen the buyer's message they answered.
            unread(seller).andExpect(jsonPath("$.conversations").value(0));
        }

        @Test
        @DisplayName("anyone else gets 404 for every part of it, not 403")
        void strangers() throws Exception {
            String id = id(contact(buyer, "Bonjour"));
            String messageId = conversationJson(buyer, id).at("/messages/0/id").asText();
            JsonNode stranger = register(uniqueEmail("msg-stranger"), UserRole.PARTICULIER);

            conversation(stranger, id).andExpect(status().isNotFound());
            reply(stranger, id, "Moi aussi").andExpect(status().isNotFound());
            mockMvc.perform(post("/api/v1/conversations/" + id + "/read")
                            .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                    .andExpect(status().isNotFound());
            report(stranger, messageId, Map.of("reason", "SPAM")).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                    .andExpect(jsonPath("$.items.length()").value(0));
        }

        @Test
        @DisplayName("a sold listing's conversation stays open; a taken-down one is read-only")
        void whenItCloses() throws Exception {
            String id = id(contact(buyer, "Bonjour"));

            act(seller, "/api/v1/listings/" + listingId + "/sold");
            conversation(buyer, id)
                    .andExpect(jsonPath("$.listing.online").value(false))
                    .andExpect(jsonPath("$.canReply").value(true));
            // And writing from the listing page continues it, though it is no longer online.
            contact(buyer, "Félicitations pour la vente").andExpect(status().isOk());

            jdbcTemplate.update("UPDATE listing SET status = 'REMOVED' WHERE id = ?::uuid", listingId);
            conversation(buyer, id).andExpect(jsonPath("$.canReply").value(false));
            reply(seller, id, "Encore là ?").andExpect(status().isConflict());
            conversation(seller, id).andExpect(jsonPath("$.messages.length()").value(2));
        }
    }

    @Nested
    @DisplayName("a sent message cannot be changed, but can be reported")
    class Reports {

        @Test
        @DisplayName("the database itself refuses to change a sent message")
        void immutable() throws Exception {
            String id = id(contact(buyer, "Bonjour"));
            String messageId = conversationJson(buyer, id).at("/messages/0/id").asText();

            assertThatThrownBy(() -> jdbcTemplate.update(
                    "UPDATE message SET body = 'Autre chose' WHERE id = ?::uuid", messageId))
                    .hasMessageContaining("a sent message cannot be changed");
        }

        @Test
        @DisplayName("the recipient reports it once; reporting it again changes nothing")
        void report() throws Exception {
            String messageId = firstMessage();

            MessagingIntegrationTest.this.report(seller, messageId, Map.of("reason", "SCAM"))
                    .andExpect(status().isNoContent());
            MessagingIntegrationTest.this.report(seller, messageId, Map.of("reason", "ABUSIVE"))
                    .andExpect(status().isNoContent());

            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message_report", Integer.class)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT reason FROM message_report", String.class)).isEqualTo("SCAM");
            conversation(seller, conversationOf(messageId)).andExpect(jsonPath("$.messages[0].reportedByMe").value(true));
        }

        @Test
        @DisplayName("nobody reports their own message, and OTHER needs a note")
        void reportRules() throws Exception {
            String messageId = firstMessage();

            MessagingIntegrationTest.this.report(buyer, messageId, Map.of("reason", "SPAM"))
                    .andExpect(status().isForbidden());
            MessagingIntegrationTest.this.report(seller, messageId, Map.of("reason", "OTHER"))
                    .andExpect(status().isBadRequest());
            MessagingIntegrationTest.this.report(seller, messageId, Map.of("reason", "OTHER", "note", "Hors sujet"))
                    .andExpect(status().isNoContent());
        }

        @Test
        @DisplayName("a moderator sees it in context, and hiding it hides it from both sides")
        void hide() throws Exception {
            String messageId = firstMessage();
            MessagingIntegrationTest.this.report(seller, messageId, Map.of("reason", "SCAM", "note", "Demande un virement"));
            JsonNode admin = admin();

            JsonNode queue = objectMapper.readTree(mockMvc.perform(get("/api/v1/admin/message-reports")
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].reason").value("SCAM"))
                    .andExpect(jsonPath("$.items[0].note").value("Demande un virement"))
                    .andExpect(jsonPath("$.items[0].context[0].reported").value(true))
                    .andExpect(jsonPath("$.items[0].context[0].body").value("Bonjour"))
                    .andReturn().getResponse().getContentAsString());
            String reportId = queue.at("/items/0/reportId").asText();

            act(admin, "/api/v1/admin/message-reports/" + reportId + "/hide");

            for (JsonNode reader : new JsonNode[] {buyer, seller}) {
                conversation(reader, conversationOf(messageId))
                        .andExpect(jsonPath("$.messages[0].hidden").value(true))
                        .andExpect(jsonPath("$.messages[0].body").doesNotExist());
            }
            // The text is kept, for the record.
            assertThat(jdbcTemplate.queryForObject("SELECT body FROM message WHERE id = ?::uuid", String.class, messageId))
                    .isEqualTo("Bonjour");
            // A hidden message has nothing to read, so it is not unread.
            unread(seller).andExpect(jsonPath("$.conversations").value(0));
            // Dealt with: off the queue, and not dealt with twice.
            mockMvc.perform(get("/api/v1/admin/message-reports").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(jsonPath("$.items.length()").value(0));
            mockMvc.perform(post("/api/v1/admin/message-reports/" + reportId + "/dismiss")
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("dismissing a report leaves the message as it was")
        void dismiss() throws Exception {
            String messageId = firstMessage();
            MessagingIntegrationTest.this.report(seller, messageId, Map.of("reason", "SPAM"));
            JsonNode admin = admin();
            String reportId = objectMapper.readTree(mockMvc.perform(get("/api/v1/admin/message-reports")
                            .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                    .andReturn().getResponse().getContentAsString()).at("/items/0/reportId").asText();

            act(admin, "/api/v1/admin/message-reports/" + reportId + "/dismiss");

            conversation(buyer, conversationOf(messageId))
                    .andExpect(jsonPath("$.messages[0].hidden").value(false))
                    .andExpect(jsonPath("$.messages[0].body").value("Bonjour"));
        }

        @Test
        @DisplayName("the queue is for administrators only")
        void adminsOnly() throws Exception {
            mockMvc.perform(get("/api/v1/admin/message-reports").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                    .andExpect(status().isForbidden());
        }

        private String firstMessage() throws Exception {
            String id = id(contact(buyer, "Bonjour"));
            return conversationJson(buyer, id).at("/messages/0/id").asText();
        }

        private String conversationOf(String messageId) {
            return jdbcTemplate.queryForObject(
                    "SELECT conversation_id::text FROM message WHERE id = ?::uuid", String.class, messageId);
        }
    }

    // ---- helpers ------------------------------------------------------------------------

    private ResultActions contact(JsonNode who, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listingId + "/conversation")
                .header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("body", body))));
    }

    private ResultActions reply(JsonNode who, String conversationId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/conversations/" + conversationId + "/messages")
                .header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("body", body))));
    }

    private ResultActions report(JsonNode who, String messageId, Map<String, String> body) throws Exception {
        return mockMvc.perform(post("/api/v1/messages/" + messageId + "/report")
                .header(HttpHeaders.AUTHORIZATION, bearer(who))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions conversation(JsonNode who, String conversationId) throws Exception {
        return mockMvc.perform(get("/api/v1/conversations/" + conversationId)
                .header(HttpHeaders.AUTHORIZATION, bearer(who)));
    }

    private JsonNode conversationJson(JsonNode who, String conversationId) throws Exception {
        return objectMapper.readTree(conversation(who, conversationId).andReturn().getResponse().getContentAsString());
    }

    private ResultActions unread(JsonNode who) throws Exception {
        return mockMvc.perform(get("/api/v1/conversations/unread").header(HttpHeaders.AUTHORIZATION, bearer(who)));
    }

    private void act(JsonNode who, String path) throws Exception {
        mockMvc.perform(post(path).header(HttpHeaders.AUTHORIZATION, bearer(who)))
                .andExpect(status().is2xxSuccessful());
    }

    private String id(ResultActions response) throws Exception {
        return objectMapper.readTree(response.andReturn().getResponse().getContentAsString()).get("id").asText();
    }

    private String userId(JsonNode authResponse) {
        return authResponse.at("/user/id").asText();
    }

    private JsonNode admin() throws Exception {
        String email = uniqueEmail("msg-admin");
        createUser(email, UserRole.ADMIN, UserStatus.ACTIVE);
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** A published listing belonging to {@code owner}, typed in by hand: the catalogue is not the subject here. */
    private String publishedListing(JsonNode owner) throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        attachReadyPhoto(id);
        act(owner, "/api/v1/listings/" + id + "/publish");
        return id;
    }
}
