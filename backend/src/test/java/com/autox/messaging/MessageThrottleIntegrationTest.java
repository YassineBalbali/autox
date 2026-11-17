package com.autox.messaging;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.support.MutableClock;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The per-account sending limits, with production's shape and small numbers: three messages
 * in a two-second window, two new conversations per window.
 *
 * <p>Time is the test's: the throttle reads a {@link MutableClock} that stands still, so every
 * request of a test falls in the same window however long it takes, and a window closes only
 * when the test steps the clock over its end. With the real clock, requests that took more
 * than two seconds on a loaded machine (four test JVMs at once) ran past the window and were
 * let through.
 */
@TestPropertySource(properties = {
        "autox.messaging.throttle.messages-per-window=3",
        "autox.messaging.throttle.message-window=PT2S",
        "autox.messaging.throttle.conversations-per-window=2",
        "autox.messaging.throttle.conversation-window=PT2S"})
@Import(MessageThrottleIntegrationTest.Time.class)
class MessageThrottleIntegrationTest extends AbstractIntegrationTest {

    private static final Duration WINDOW = Duration.ofSeconds(2);

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(start());
        }
    }

    @Autowired MutableClock clock;

    /**
     * Now, on a whole millisecond: the database keeps a window's start to the microsecond, and
     * a start with digits below that would come back a fraction early, shortening the
     * remaining wait the refusal reports by a second.
     */
    static Instant start() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    private JsonNode seller;
    private JsonNode buyer;

    @BeforeEach
    void setUp() throws Exception {
        clock.set(start());
        seller = register(uniqueEmail("throttle-seller"), UserRole.PROFESSIONNEL);
        buyer = register(uniqueEmail("throttle-buyer"), UserRole.PARTICULIER);
    }

    @Test
    @DisplayName("the message after the limit is refused with 429 and how long to wait")
    void messageLimit() throws Exception {
        String listing = publishedListing();
        for (int i = 0; i < 3; i++) {
            contact(listing, "Message " + i).andExpect(status().isOk());
        }

        contact(listing, "Un de trop")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                // The whole window is still to run: two seconds, rounded up past its end.
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3"));
    }

    @Test
    @DisplayName("a fixed window: a steady writer is let through again once it has run")
    void windowResets() throws Exception {
        String listing = publishedListing();
        for (int i = 0; i < 3; i++) {
            contact(listing, "Message " + i).andExpect(status().isOk());
        }
        contact(listing, "Un de trop").andExpect(status().isTooManyRequests());

        // A millisecond before the window's end it is still closed…
        clock.advance(WINDOW.minusMillis(1));
        contact(listing, "Presque").andExpect(status().isTooManyRequests());

        // …and at its end a new one opens.
        clock.advance(Duration.ofMillis(1));
        contact(listing, "Après la fenêtre").andExpect(status().isOk());
    }

    @Test
    @DisplayName("new conversations have their own, lower cap")
    void conversationLimit() throws Exception {
        String first = publishedListing();
        String second = publishedListing();
        String third = publishedListing();

        contact(first, "Bonjour").andExpect(status().isOk());
        contact(second, "Bonjour").andExpect(status().isOk());
        contact(third, "Bonjour").andExpect(status().isTooManyRequests());
        // Continuing a conversation is not starting one: the message cap still has room.
        contact(first, "Et sinon ?").andExpect(status().isOk());
    }

    @Test
    @DisplayName("each account has its own count")
    void perAccount() throws Exception {
        String listing = publishedListing();
        for (int i = 0; i < 3; i++) {
            contact(listing, "Message " + i).andExpect(status().isOk());
        }
        String conversation = jdbcTemplate.queryForObject("SELECT id::text FROM conversation", String.class);

        // The buyer is at the limit; the seller has written nothing.
        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Bonjour\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a refused message is not counted: an empty one leaves the limit untouched")
    void refusedNotCounted() throws Exception {
        String listing = publishedListing();
        for (int i = 0; i < 3; i++) {
            contact(listing, " ").andExpect(status().isBadRequest());
        }

        contact(listing, "Bonjour").andExpect(status().isOk());
    }

    private ResultActions contact(String listingId, String body) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listingId + "/conversation")
                .header(HttpHeaders.AUTHORIZATION, bearer(buyer))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("body", body))));
    }

    private String publishedListing() throws Exception {
        String body = mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(seller))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(body).get("id").asText();
        attachReadyPhoto(id);
        mockMvc.perform(post("/api/v1/listings/" + id + "/publish").header(HttpHeaders.AUTHORIZATION, bearer(seller)))
                .andExpect(status().isOk());
        return id;
    }
}
