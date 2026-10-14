package com.autox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.throttle.ThrottleCounters;
import com.autox.throttle.ThrottleScope;
import com.autox.user.UserRole;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The cap on registrations from one client address.
 *
 * <p>Dropped to three a window here; production allows five an hour. The properties are
 * otherwise production's, which is the point of switching the throttle on for this class
 * rather than testing a stand-in.
 */
@TestPropertySource(properties = {
        "autox.auth.registration-throttle.enabled=true",
        "autox.auth.registration-throttle.max-attempts-per-window=3",
        "autox.auth.registration-throttle.window=PT1H"})
class RegistrationThrottleIntegrationTest extends AbstractIntegrationTest {

    @Autowired ThrottleCounters counters;

    @Test
    @DisplayName("registrations within the cap succeed; the next is refused with 429 and Retry-After")
    void refusesPastTheCap() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            register(uniqueEmail("within"), "203.0.113.7").andExpect(status().isCreated());
        }

        String refused = uniqueEmail("one-too-many");
        register(refused, "203.0.113.7")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());

        // Refused before anything was written: no account, and so no hash either.
        assertThat(users.existsByEmailIgnoreCase(refused)).isFalse();
    }

    @Test
    @DisplayName("the cap is per address: another caller is not affected")
    void perAddress() throws Exception {
        for (int attempt = 0; attempt < 4; attempt++) {
            register(uniqueEmail("noisy"), "203.0.113.8");
        }

        register(uniqueEmail("neighbour"), "203.0.113.9").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("failed attempts count too, so the endpoint cannot be used to probe or to burn hashes")
    void failuresCount() throws Exception {
        String taken = uniqueEmail("taken");
        register(taken, "203.0.113.10").andExpect(status().isCreated());
        register(taken, "203.0.113.10").andExpect(status().isConflict());
        register(taken, "203.0.113.10").andExpect(status().isConflict());

        // The fourth attempt is refused as rate-limited — and no longer says whether the
        // address is registered, which is what the first three were being used to ask.
        register(taken, "203.0.113.10")
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @DisplayName("an IPv6 caller is keyed on its /64, so rotating within one does not escape the cap")
    void ipv6Prefix() throws Exception {
        for (int attempt = 0; attempt < 3; attempt++) {
            register(uniqueEmail("v6"), "2001:db8:1:2::" + (attempt + 1)).andExpect(status().isCreated());
        }

        register(uniqueEmail("v6-rotated"), "2001:db8:1:2:ffff::99").andExpect(status().isTooManyRequests());
        register(uniqueEmail("v6-elsewhere"), "2001:db8:1:3::1").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("the count is kept in the shared counter table under its own scope")
    void ownScope() throws Exception {
        register(uniqueEmail("counted"), "203.0.113.11").andExpect(status().isCreated());

        assertThat(counters.find(ThrottleScope.REGISTRATION_IP, "203.0.113.11"))
                .hasValueSatisfying(counter -> assertThat(counter.eventCount()).isEqualTo(1));
        // Creating an account is not a failed sign-in, and is not counted as one.
        assertThat(counters.find(ThrottleScope.IP, "203.0.113.11")).isEmpty();
    }

    private ResultActions register(String email, String address) throws Exception {
        Map<String, Object> payload = registrationPayload(email, UserRole.PARTICULIER);
        // The address as the container resolved it; the proxy headers that feed it are
        // covered on a real port by ClientAddressIntegrationTest.
        return mockMvc.perform(post("/api/v1/auth/register")
                .with(request -> {
                    request.setRemoteAddr(address);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }
}
