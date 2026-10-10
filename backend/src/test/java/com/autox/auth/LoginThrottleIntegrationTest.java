package com.autox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.throttle.ThrottleCounters;
import com.autox.auth.throttle.LoginThrottleProperties;
import com.autox.throttle.ThrottleScope;
import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Progressive login throttling.
 *
 * <p>The test profile sets a low account threshold and a one-second initial delay, so the
 * escalation is observable without the suite sleeping for minutes. The policy arithmetic
 * itself is checked directly, independent of configuration.
 */
class LoginThrottleIntegrationTest extends AbstractIntegrationTest {

    @Autowired LoginThrottleProperties throttleProperties;
    @Autowired ThrottleCounters counters;

    @Test
    @DisplayName("failures below the threshold are not throttled")
    void failuresBelowThresholdAreAllowed() throws Exception {
        String email = uniqueEmail("under-threshold");
        register(email, UserRole.PARTICULIER);

        for (int attempt = 1; attempt <= throttleProperties.accountThreshold(); attempt++) {
            failLogin(email).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
    }

    @Test
    @DisplayName("the attempt past the threshold is refused with 429 and a Retry-After header")
    void attemptPastThresholdIsThrottled() throws Exception {
        String email = uniqueEmail("throttled");
        register(email, UserRole.PARTICULIER);

        for (int attempt = 1; attempt <= throttleProperties.accountThreshold(); attempt++) {
            failLogin(email).andExpect(status().isUnauthorized());
        }
        // One more failure crosses the threshold and arms the delay.
        failLogin(email).andExpect(status().isUnauthorized());

        failLogin(email)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("the correct password is refused too while the delay is active — the block is not a password check")
    void throttleAppliesEvenToTheCorrectPassword() throws Exception {
        String email = uniqueEmail("blocked-correct");
        register(email, UserRole.PARTICULIER);

        for (int attempt = 0; attempt <= throttleProperties.accountThreshold(); attempt++) {
            failLogin(email).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
    }

    @Test
    @DisplayName("waiting out the delay restores access, so a real user is never locked out")
    void delayExpiresAndAccessIsRestored() throws Exception {
        String email = uniqueEmail("waits-it-out");
        register(email, UserRole.PARTICULIER);

        for (int attempt = 0; attempt <= throttleProperties.accountThreshold(); attempt++) {
            failLogin(email).andExpect(status().isUnauthorized());
        }
        failLogin(email).andExpect(status().isTooManyRequests());

        // The test profile's initial delay is one second.
        Thread.sleep(throttleProperties.accountInitialDelay().plusMillis(1200).toMillis());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a successful sign-in clears the account counter")
    void successClearsTheCounter() throws Exception {
        String email = uniqueEmail("cleared");
        register(email, UserRole.PARTICULIER);

        failLogin(email).andExpect(status().isUnauthorized());
        failLogin(email).andExpect(status().isUnauthorized());
        assertThat(countersFor(email)).isPresent();

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isOk());

        assertThat(countersFor(email)).isEmpty();
    }

    @Test
    @DisplayName("counters survive the failed request's rolled-back transaction")
    void counterIsPersistedDespiteTheThrownException() throws Exception {
        String email = uniqueEmail("persisted");
        register(email, UserRole.PARTICULIER);

        failLogin(email).andExpect(status().isUnauthorized());

        // A login failure ends in an exception; if the increment shared that
        // transaction it would be rolled back and throttling would never engage.
        assertThat(countersFor(email)).isPresent()
                .get().extracting(ThrottleCounters.Counter::eventCount).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown address is throttled identically, so 429 never implies the account exists")
    void unknownAddressesAreThrottledToo() throws Exception {
        String ghost = uniqueEmail("ghost-account");

        for (int attempt = 0; attempt <= throttleProperties.accountThreshold(); attempt++) {
            failLogin(ghost).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }

        failLogin(ghost)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));

        assertThat(countersFor(ghost)).isPresent();
    }

    @Test
    @DisplayName("throttling one account does not affect another")
    void throttlingIsPerAccount() throws Exception {
        String victim = uniqueEmail("victim");
        String bystander = uniqueEmail("bystander");
        register(victim, UserRole.PARTICULIER);
        register(bystander, UserRole.PARTICULIER);

        for (int attempt = 0; attempt <= throttleProperties.accountThreshold(); attempt++) {
            failLogin(victim).andExpect(status().isUnauthorized());
        }
        failLogin(victim).andExpect(status().isTooManyRequests());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", bystander, "password", "correct-horse-battery"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the email is not stored in clear text in the throttle table")
    void emailIsHashedInTheCounterTable() throws Exception {
        String email = uniqueEmail("privacy");
        failLogin(email).andExpect(status().isUnauthorized());

        Integer clearTextRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM throttle_counter WHERE scope_key = ?", Integer.class, email);
        assertThat(clearTextRows).isZero();
        assertThat(countersFor(email)).isPresent();
    }

    @Test
    @DisplayName("the delay schedule doubles and then caps")
    void delayScheduleDoublesThenCaps() {
        Duration initial = Duration.ofSeconds(30);
        Duration max = Duration.ofMinutes(15);

        assertThat(LoginThrottleProperties.delayFor(4, 4, initial, max)).isZero();
        assertThat(LoginThrottleProperties.delayFor(5, 4, initial, max)).isEqualTo(Duration.ofSeconds(30));
        assertThat(LoginThrottleProperties.delayFor(6, 4, initial, max)).isEqualTo(Duration.ofMinutes(1));
        assertThat(LoginThrottleProperties.delayFor(7, 4, initial, max)).isEqualTo(Duration.ofMinutes(2));
        assertThat(LoginThrottleProperties.delayFor(8, 4, initial, max)).isEqualTo(Duration.ofMinutes(4));
        assertThat(LoginThrottleProperties.delayFor(9, 4, initial, max)).isEqualTo(Duration.ofMinutes(8));
        // Capped from here on, however long the attack runs.
        assertThat(LoginThrottleProperties.delayFor(10, 4, initial, max)).isEqualTo(max);
        assertThat(LoginThrottleProperties.delayFor(5000, 4, initial, max)).isEqualTo(max);
    }

    private ResultActions failLogin(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("email", email, "password", "definitely-the-wrong-password"))));
    }

    private java.util.Optional<ThrottleCounters.Counter> countersFor(String email) {
        return counters.find(ThrottleScope.ACCOUNT, accountKeyOf(email));
    }

    /** Mirrors the service's key derivation, so the test reads the row the service wrote. */
    private static String accountKeyOf(String email) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(
                    email.trim().toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
