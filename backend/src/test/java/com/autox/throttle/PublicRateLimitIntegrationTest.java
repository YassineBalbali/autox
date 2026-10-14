package com.autox.throttle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * Per-IP rate limiting of the public read endpoints.
 *
 * <p>The limit is dropped to 3 a window here. The production number is 300 and deliberately
 * out of a real visitor's reach, which is exactly why it cannot be exercised at its real
 * value without the suite making three hundred requests.
 */
@TestPropertySource(properties = {
        "autox.public-api.rate-limit.enabled=true",
        "autox.public-api.rate-limit.requests-per-window=3",
        "autox.public-api.rate-limit.window=PT1M",
        "autox.public-api.rate-limit.block-for=PT30S"})
class PublicRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired ThrottleCounters counters;

    @Test
    @DisplayName("requests within the limit are served normally")
    void withinLimit() throws Exception {
        for (int request = 0; request < 3; request++) {
            mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("the request past the limit is refused with 429, RATE_LIMITED and Retry-After")
    void refusesPastTheLimit() throws Exception {
        for (int request = 0; request < 3; request++) {
            mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
    }

    @Test
    @DisplayName("the client address is the key, and counts per address")
    void clientAddressIsTheKey() throws Exception {
        // The address as the container resolved it. Behind a proxy that is Tomcat's
        // RemoteIpValve's work, which MockMvc does not run; ClientAddressIntegrationTest
        // covers the proxy headers on a real port.
        for (int request = 0; request < 3; request++) {
            mockMvc.perform(get("/api/v1/listings").with(from("203.0.113.7")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/listings").with(from("203.0.113.7")))
                .andExpect(status().isTooManyRequests());

        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "203.0.113.7")).isPresent();

        // A different client is unaffected.
        mockMvc.perform(get("/api/v1/listings").with(from("203.0.113.8")))
                .andExpect(status().isOk());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    @Test
    @DisplayName("the refusal keeps the problem+json contract, not a bare container error")
    void refusalUsesTheErrorContract() throws Exception {
        exhaustTheLimit();

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Content-Type",
                        org.hamcrest.Matchers.containsString("application/problem+json")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.instance").value("/api/v1/listings"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("the block covers every public read endpoint, not only the one that tripped it")
    void blockIsPerAddressNotPerPath() throws Exception {
        exhaustTheLimit();

        mockMvc.perform(get("/api/v1/reference/enums")).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/v1/listings/" + java.util.UUID.randomUUID()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("reference endpoints count toward the same limit")
    void referenceEndpointsAreCounted() throws Exception {
        for (int request = 0; request < 3; request++) {
            mockMvc.perform(get("/api/v1/reference/countries")).andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/reference/countries")).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("authenticated writes to the same path prefix are not counted")
    void writesAreNotCounted() throws Exception {
        String token = bearer(register(uniqueEmail("ratelimit-writer"), UserRole.PARTICULIER));

        // Four POSTs to /api/v1/listings — past the read limit, and none of them a read.
        for (int request = 0; request < 4; request++) {
            mockMvc.perform(post("/api/v1/listings")
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    com.autox.support.ListingTestFixtures.freeTextListingPayload())))
                    .andExpect(status().isCreated());
        }

        // And a read afterwards still has its full allowance.
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "127.0.0.1"))
                .get().extracting(ThrottleCounters.Counter::eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("the seller's own listing views are excluded from the public limit")
    void ownListingViewsAreExcluded() throws Exception {
        String token = bearer(register(uniqueEmail("ratelimit-owner"), UserRole.PARTICULIER));

        for (int request = 0; request < 5; request++) {
            mockMvc.perform(get("/api/v1/listings/mine").header("Authorization", token))
                    .andExpect(status().isOk());
        }
        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "127.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("counting is recorded under PUBLIC_READ, keyed by address")
    void countsUnderItsOwnScope() throws Exception {
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());

        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "127.0.0.1"))
                .get().extracting(ThrottleCounters.Counter::eventCount).isEqualTo(2);
        // The login scopes are untouched: one table, independent counters.
        assertThat(counters.find(ThrottleScope.IP, "127.0.0.1")).isEmpty();
    }

    @Test
    @DisplayName("the counter survives the refusal, which is what the login throttle got wrong first")
    void counterIsCommittedDespiteTheRefusal() throws Exception {
        exhaustTheLimit();
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isTooManyRequests());

        // Three allowed plus two refused is five counted requests. If the write rolled back
        // with the refusal the count would be stuck at three, the limit, for ever.
        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "127.0.0.1"))
                .get().extracting(ThrottleCounters.Counter::eventCount).isEqualTo(5);
    }

    @Test
    @DisplayName("a quiet address starts from zero again once the window passes")
    void windowDecays() throws Exception {
        exhaustTheLimit();
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isTooManyRequests());

        // Rather than sleeping out a one-minute window, age the counter past it and clear the
        // block the way its expiry would. What is under test is the decay arithmetic.
        jdbcTemplate.update("""
                UPDATE throttle_counter
                   SET last_event_at = now() - interval '5 minutes', blocked_until = NULL
                 WHERE scope = 'PUBLIC_READ'
                """);

        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "127.0.0.1"))
                .get().extracting(ThrottleCounters.Counter::eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("the sweeper purges public counters along with login ones")
    void sweeperPurgesPublicCounters() throws Exception {
        mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());

        jdbcTemplate.update(
                "UPDATE throttle_counter SET last_event_at = now() - interval '48 hours'");
        assertThat(counters.deleteOlderThan(java.time.Instant.now().minusSeconds(3600)))
                .isGreaterThanOrEqualTo(1);
        assertThat(counters.find(ThrottleScope.PUBLIC_READ, "127.0.0.1")).isEmpty();
    }

    private void exhaustTheLimit() throws Exception {
        for (int request = 0; request < 3; request++) {
            mockMvc.perform(get("/api/v1/listings")).andExpect(status().isOk());
        }
    }
}
