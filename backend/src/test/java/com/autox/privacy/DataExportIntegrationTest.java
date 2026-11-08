package com.autox.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/** "Télécharger mes données": one JSON file with what AutoX holds about the account, and nobody else's. */
class DataExportIntegrationTest extends PrivacyTestSupport {

    @Test
    @DisplayName("the account, its listings with their photos, its conversations, saved searches and sessions")
    void contents() throws Exception {
        String email = uniqueEmail("exporting");
        JsonNode me = register(email, UserRole.PARTICULIER);
        JsonNode other = register(uniqueEmail("other"), UserRole.PARTICULIER);
        String mine = publishedListing(me);
        String theirs = publishedListing(other);
        String conversation = contact(me, theirs, "Bonjour, je suis intéressé.");
        reply(other, conversation, "Avec plaisir.");
        saveSearch(me);

        JsonNode export = json(export(me)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.matchesPattern(
                        "attachment; filename=\"autox-mes-donnees-\\d{4}-\\d{2}-\\d{2}\\.json\"")))
                .andReturn());

        assertThat(export.at("/account/email").asText()).isEqualTo(email);
        assertThat(export.at("/account").has("passwordHash")).isFalse();
        assertThat(export.get("listings")).hasSize(1);
        assertThat(export.at("/listings/0/id").asText()).isEqualTo(mine);
        assertThat(export.at("/listings/0/photos/0/url").asText()).startsWith("http");
        assertThat(export.at("/listings/0/priceHistory").isArray()).isTrue();
        assertThat(export.at("/conversations/0/counterpart").asText()).isEqualTo(other.at("/user/displayName").asText());
        assertThat(export.at("/conversations/0/messages/0/text").asText()).isEqualTo("Bonjour, je suis intéressé.");
        assertThat(export.at("/conversations/0/messages/0/sentByMe").asBoolean()).isTrue();
        assertThat(export.at("/conversations/0/messages/1/text").asText()).isEqualTo("Avec plaisir.");
        assertThat(export.at("/savedSearches/0/name").asText()).isEqualTo("Mon alerte");
        assertThat(export.at("/savedSearches/0/criteria").isObject()).isTrue();
        assertThat(export.get("sessions")).isNotEmpty();

        // Nothing of the other account but its display name in the conversation.
        assertThat(export.toString()).doesNotContain(theirs).doesNotContain(other.at("/user/email").asText());
    }

    @Test
    @DisplayName("three an hour; the fourth answers 429 with when to try again")
    void rateLimited() throws Exception {
        JsonNode me = register(uniqueEmail("eager"), UserRole.PARTICULIER);
        for (int i = 0; i < DataExportService.PER_WINDOW; i++) {
            export(me).andExpect(status().isOk());
        }
        export(me)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("needs a session")
    void anonymous() throws Exception {
        mockMvc.perform(get("/api/v1/me/export")).andExpect(status().isUnauthorized());
    }

    private ResultActions export(JsonNode who) throws Exception {
        return mockMvc.perform(get("/api/v1/me/export").header(HttpHeaders.AUTHORIZATION, bearer(who)));
    }
}
