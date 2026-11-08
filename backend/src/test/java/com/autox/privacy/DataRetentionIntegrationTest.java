package com.autox.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The daily retention sweep: deleted listings after 30 days, conversations a year after their last message. */
class DataRetentionIntegrationTest extends PrivacyTestSupport {

    @Autowired DataRetentionSweeper retention;

    private JsonNode seller;
    private JsonNode buyer;

    @BeforeEach
    void setUp() throws Exception {
        seller = register(uniqueEmail("ret-seller"), UserRole.PARTICULIER);
        buyer = register(uniqueEmail("ret-buyer"), UserRole.PARTICULIER);
    }

    @Test
    @DisplayName("a listing its seller deleted is erased with its photos 30 days later, not before")
    void deletedListings() throws Exception {
        String old = publishedListing(seller);
        String recent = publishedListing(register(uniqueEmail("ret-other"), UserRole.PARTICULIER));
        String live = publishedListing(register(uniqueEmail("ret-live"), UserRole.PARTICULIER));
        mockMvc.perform(delete("/api/v1/listings/" + old).header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().is2xxSuccessful());
        jdbcTemplate.update("UPDATE listing SET deleted_at = now() - interval '31 days' WHERE id = ?::uuid", old);
        jdbcTemplate.update("UPDATE listing SET deleted_at = now() - interval '29 days' WHERE id = ?::uuid", recent);
        List<String> oldKeys = storedKeys(old);

        DataRetentionSweeper.Swept swept = retention.apply(Instant.now());

        assertThat(swept.listings()).isOne();
        assertThat(swept.objects()).isEqualTo(oldKeys.size());
        assertThat(exists("listing", old)).isFalse();
        assertThat(oldKeys).allSatisfy(key -> assertThat(stored(key)).isFalse());
        assertThat(exists("listing", recent)).isTrue();
        assertThat(storedKeys(recent)).allSatisfy(key -> assertThat(stored(key)).isTrue());
        assertThat(exists("listing", live)).isTrue();
    }

    @Test
    @DisplayName("a conversation outlives its erased listing")
    void conversationOutlivesItsListing() throws Exception {
        String listing = publishedListing(seller);
        String conversation = contact(buyer, listing, "Bonjour");
        jdbcTemplate.update("UPDATE listing SET deleted_at = now() - interval '40 days' WHERE id = ?::uuid", listing);

        retention.apply(Instant.now());

        assertThat(exists("listing", listing)).isFalse();
        JsonNode seen = conversation(buyer, conversation);
        assertThat(seen.path("listing").isMissingNode()).isTrue();
        assertThat(seen.at("/messages/0/body").asText()).isEqualTo("Bonjour");
    }

    @Test
    @DisplayName("a conversation is erased a year after its last message, unless a message in it is under an open report")
    void conversations() throws Exception {
        String listing = publishedListing(seller);
        String old = contact(buyer, listing, "Il y a longtemps");
        JsonNode other = register(uniqueEmail("ret-reporter"), UserRole.PARTICULIER);
        String reported = contact(other, listing, "Arnaque");
        JsonNode third = register(uniqueEmail("ret-recent"), UserRole.PARTICULIER);
        String recent = contact(third, listing, "Récent");
        String reportedMessage = conversation(other, reported).at("/messages/0/id").asText();
        mockMvc.perform(post("/api/v1/messages/" + reportedMessage + "/report").header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"SPAM\"}"))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("UPDATE conversation SET last_message_at = now() - interval '366 days' WHERE id IN (?::uuid, ?::uuid)",
                old, reported);
        jdbcTemplate.update("UPDATE conversation SET last_message_at = now() - interval '364 days' WHERE id = ?::uuid", recent);

        DataRetentionSweeper.Swept swept = retention.apply(Instant.now());

        assertThat(swept.conversations()).isOne();
        assertThat(exists("conversation", old)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message WHERE conversation_id = ?::uuid",
                Integer.class, old)).isZero();
        assertThat(exists("conversation", reported)).isTrue();
        assertThat(exists("conversation", recent)).isTrue();
    }

    @Test
    @DisplayName("the rules are the ones the privacy page states")
    void rules() {
        assertThat(DataRetentionSweeper.DELETED_LISTINGS).isEqualTo(Duration.ofDays(30));
        assertThat(DataRetentionSweeper.CONVERSATIONS).isEqualTo(Duration.ofDays(365));
    }

    private boolean exists(String table, String id) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?::uuid", Integer.class, id) == 1;
    }
}
