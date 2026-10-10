package com.autox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.support.RecordingEmailSender;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

/**
 * The account operations that were missing: change password, forgotten-password reset, and
 * the session list.
 *
 * <p>Before these existed, registration was the only account operation that worked — a user
 * who forgot their password was permanently locked out, and a user who lost a phone could
 * not end that session. The tests below are weighted accordingly: most of them are about
 * what stops working afterwards, because that is the part a half-built implementation gets
 * wrong.
 */
@TestPropertySource(properties = "autox.mail.provider = recording")
class AccountOperationsIntegrationTest extends AbstractIntegrationTest {

    private static final String ORIGINAL_PASSWORD = "correct-horse-battery";
    private static final String NEW_PASSWORD = "an-entirely-different-passphrase";

    @TestConfiguration
    static class RecordingMailConfiguration {

        @Bean
        RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }

    @Autowired RecordingEmailSender emails;
    @Autowired com.autox.auth.password.PasswordResetTokenRepository resetTokens;

    @BeforeEach
    void clearMailbox() {
        emails.clear();
    }

    // --- change password --------------------------------------------------

    @Test
    @DisplayName("changing the password requires the current one")
    void changePasswordRejectsAWrongCurrentPassword() throws Exception {
        JsonNode session = register(uniqueEmail("pw-wrong"), UserRole.PARTICULIER);

        // A valid access token is not enough. It may be a stolen one, and taking the
        // account over permanently is the most valuable thing to do with fifteen minutes.
        mockMvc.perform(post("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", "not-the-right-one",
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_INCORRECT"));
    }

    @Test
    @DisplayName("the new password must differ from the current one")
    void changePasswordRejectsTheSamePassword() throws Exception {
        JsonNode session = register(uniqueEmail("pw-same"), UserRole.PARTICULIER);

        mockMvc.perform(post("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", ORIGINAL_PASSWORD,
                                "newPassword", ORIGINAL_PASSWORD))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("changing the password switches which credentials work")
    void changePasswordSwitchesCredentials() throws Exception {
        String email = uniqueEmail("pw-change");
        JsonNode session = register(email, UserRole.PARTICULIER);

        JsonNode replacement = changePassword(session, ORIGINAL_PASSWORD, NEW_PASSWORD);
        assertThat(replacement.get("accessToken").asText()).isNotBlank();
        assertThat(replacement.get("refreshToken").asText()).isNotBlank();

        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, ORIGINAL_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    @DisplayName("changing the password ends every existing session, including the caller's")
    void changePasswordRevokesEverySession() throws Exception {
        String email = uniqueEmail("pw-sessions");
        JsonNode first = register(email, UserRole.PARTICULIER);
        JsonNode second = loginSession(email, ORIGINAL_PASSWORD, "the other phone");

        JsonNode replacement = changePassword(first, ORIGINAL_PASSWORD, NEW_PASSWORD);

        // The point of the operation: a password change is the response to "someone else
        // may have my credentials", so leaving any older session alive would defeat it.
        refresh(first.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        refresh(second.get("refreshToken").asText()).andExpect(status().isUnauthorized());
        // And the pair handed back works, so the caller is not signed out of their own app.
        refresh(replacement.get("refreshToken").asText()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("changing the password needs authentication")
    void changePasswordNeedsAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/me/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", ORIGINAL_PASSWORD,
                                "newPassword", NEW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    // --- forgotten password ----------------------------------------------

    @Test
    @DisplayName("a reset request for an unknown address is accepted and sends nothing")
    void forgotPasswordIsSilentAboutUnknownAddresses() throws Exception {
        requestReset("nobody-" + java.util.UUID.randomUUID() + "@autox.test")
                .andExpect(status().isAccepted());

        assertThat(emails.sent()).isEmpty();
    }

    @Test
    @DisplayName("a known and an unknown address are indistinguishable from the response")
    void forgotPasswordAnswersIdenticallyEitherWay() throws Exception {
        String registered = uniqueEmail("pw-oracle");
        register(registered, UserRole.PARTICULIER);

        var known = requestReset(registered).andReturn().getResponse();
        var unknown = requestReset("nobody-" + java.util.UUID.randomUUID() + "@autox.test")
                .andReturn().getResponse();

        // If these ever differ, the endpoint has become a free list of who has an account.
        assertThat(known.getStatus()).isEqualTo(unknown.getStatus());
        assertThat(known.getContentAsString()).isEqualTo(unknown.getContentAsString());
    }

    @Test
    @DisplayName("a reset link is emailed, and the token is not stored in a usable form")
    void forgotPasswordEmailsALinkAndStoresOnlyAHash() throws Exception {
        String email = uniqueEmail("pw-link");
        register(email, UserRole.PARTICULIER);

        requestReset(email).andExpect(status().isAccepted());

        assertThat(emails.sent()).hasSize(1);
        assertThat(emails.last().to()).isEqualTo(email);
        assertThat(emails.last().body()).contains("https://autox.test/reset-password?token=");
        String token = emails.lastResetToken();

        // The row must not contain the token itself: a dump of this table cannot be a set
        // of working reset links.
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT token_hash FROM password_reset_token");
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("token_hash").toString())
                .isNotEqualTo(token)
                .matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("the emailed token sets a new password")
    void resetPasswordWithTheEmailedToken() throws Exception {
        String email = uniqueEmail("pw-reset");
        register(email, UserRole.PARTICULIER);
        requestReset(email).andExpect(status().isAccepted());

        resetPassword(emails.lastResetToken(), NEW_PASSWORD).andExpect(status().isNoContent());

        login(email, NEW_PASSWORD).andExpect(status().isOk());
        login(email, ORIGINAL_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a reset token works once")
    void resetTokenIsSingleUse() throws Exception {
        String email = uniqueEmail("pw-reuse");
        register(email, UserRole.PARTICULIER);
        requestReset(email).andExpect(status().isAccepted());
        String token = emails.lastResetToken();

        resetPassword(token, NEW_PASSWORD).andExpect(status().isNoContent());
        resetPassword(token, "a-third-password-entirely")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));

        // The second attempt changed nothing.
        login(email, NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("asking again invalidates the earlier link")
    void newRequestSupersedesTheOldToken() throws Exception {
        String email = uniqueEmail("pw-supersede");
        register(email, UserRole.PARTICULIER);

        requestReset(email).andExpect(status().isAccepted());
        String firstToken = emails.lastResetToken();
        requestReset(email).andExpect(status().isAccepted());
        String secondToken = emails.lastResetToken();

        assertThat(secondToken).isNotEqualTo(firstToken);
        // Otherwise a user who clicks "forgot password" three times leaves three live keys
        // to their account sitting in three emails.
        resetPassword(firstToken, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        resetPassword(secondToken, NEW_PASSWORD).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("an expired reset token is refused")
    void expiredResetTokenIsRefused() throws Exception {
        String email = uniqueEmail("pw-expired");
        register(email, UserRole.PARTICULIER);
        requestReset(email).andExpect(status().isAccepted());
        String token = emails.lastResetToken();

        // Aged rather than waited for: the TTL is the production thirty minutes, and a
        // suite that sleeps through policy windows is a suite nobody runs.
        jdbcTemplate.update("UPDATE password_reset_token SET expires_at = now() - interval '1 minute'");

        resetPassword(token, NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        login(email, ORIGINAL_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a reset ends every session")
    void resetRevokesEverySession() throws Exception {
        String email = uniqueEmail("pw-reset-sessions");
        JsonNode session = register(email, UserRole.PARTICULIER);
        requestReset(email).andExpect(status().isAccepted());

        resetPassword(emails.lastResetToken(), NEW_PASSWORD).andExpect(status().isNoContent());

        refresh(session.get("refreshToken").asText()).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a suspended account is not sent a reset link")
    void suspendedAccountGetsNoLink() throws Exception {
        String email = uniqueEmail("pw-suspended");
        createUser(email, UserRole.PARTICULIER, UserStatus.SUSPENDED);

        requestReset(email).andExpect(status().isAccepted());

        // It could not sign in with the new password anyway, and mailing one invites a
        // support conversation that ends in "your account is suspended".
        assertThat(emails.sent()).isEmpty();
    }

    @Test
    @DisplayName("repeated reset requests for one account are refused")
    void resetRequestsAreThrottledPerAccount() throws Exception {
        String email = uniqueEmail("pw-flood");
        register(email, UserRole.PARTICULIER);

        for (int attempt = 0; attempt < 3; attempt++) {
            requestReset(email).andExpect(status().isAccepted());
        }

        // The caller does not have to own the address they typed, so without this the
        // endpoint is a way to bury someone else's inbox.
        requestReset(email)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .header().exists(HttpHeaders.RETRY_AFTER));
        assertThat(emails.sent()).hasSize(3);
    }

    @Test
    @DisplayName("the throttle counts unknown addresses too, so a 429 reveals nothing")
    void throttleAppliesToUnknownAddressesAsWell() throws Exception {
        String unknown = "nobody-" + java.util.UUID.randomUUID() + "@autox.test";

        for (int attempt = 0; attempt < 3; attempt++) {
            requestReset(unknown).andExpect(status().isAccepted());
        }
        // A cap that only applied to real accounts would leak existence through the 429.
        requestReset(unknown).andExpect(status().isTooManyRequests());
    }

    // --- sessions ---------------------------------------------------------

    @Test
    @DisplayName("the session list shows one entry per sign-in, newest first")
    void sessionListShowsEverySignIn() throws Exception {
        String email = uniqueEmail("sessions-list");
        register(email, UserRole.PARTICULIER);
        loginSession(email, ORIGINAL_PASSWORD, "iPhone 15");
        JsonNode latest = loginSession(email, ORIGINAL_PASSWORD, "MacBook Pro");

        mockMvc.perform(get("/api/v1/me/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(latest)))
                .andExpect(status().isOk())
                // Registration plus two logins.
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].deviceLabel").value("MacBook Pro"))
                .andExpect(jsonPath("$[1].deviceLabel").value("iPhone 15"))
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].expiresAt").isNotEmpty())
                // Nothing in the list is a credential, and the token is not in the database
                // in a usable form to begin with.
                .andExpect(jsonPath("$[0].refreshToken").doesNotExist())
                .andExpect(jsonPath("$[0].tokenHash").doesNotExist());
    }

    @Test
    @DisplayName("revoking a session kills that device and leaves the others alone")
    void revokingASessionAffectsOnlyThatDevice() throws Exception {
        String email = uniqueEmail("sessions-revoke");
        JsonNode lost = loginFirst(email, "the phone left in a taxi");
        JsonNode kept = loginSession(email, ORIGINAL_PASSWORD, "the laptop at home");

        String lostId = sessionIdByLabel(kept, "the phone left in a taxi");
        mockMvc.perform(delete("/api/v1/me/sessions/" + lostId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(kept)))
                .andExpect(status().isNoContent());

        refresh(lost.get("refreshToken").asText())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
        refresh(kept.get("refreshToken").asText()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a revoked session drops off the list")
    void revokedSessionLeavesTheList() throws Exception {
        String email = uniqueEmail("sessions-gone");
        loginFirst(email, "first device");
        JsonNode current = loginSession(email, ORIGINAL_PASSWORD, "second device");

        mockMvc.perform(delete("/api/v1/me/sessions/" + sessionIdByLabel(current, "first device"))
                        .header(HttpHeaders.AUTHORIZATION, bearer(current)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(current)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].deviceLabel",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("first device"))));
    }

    @Test
    @DisplayName("one account cannot end another's session")
    void cannotRevokeSomeoneElsesSession() throws Exception {
        JsonNode victim = loginFirst(uniqueEmail("sessions-victim"), "victim phone");
        JsonNode attacker = register(uniqueEmail("sessions-attacker"), UserRole.PARTICULIER);

        String victimSessionId = sessionIdByLabel(victim, "victim phone");

        // 404, not 403: there is nothing to be learned here by guessing identifiers.
        mockMvc.perform(delete("/api/v1/me/sessions/" + victimSessionId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(attacker)))
                .andExpect(status().isNotFound());
        refresh(victim.get("refreshToken").asText()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("ending an already-ended session is a 404, not a second success")
    void revokingTwiceIsNotFound() throws Exception {
        String email = uniqueEmail("sessions-twice");
        loginFirst(email, "spare device");
        JsonNode current = loginSession(email, ORIGINAL_PASSWORD, "current device");
        String spare = sessionIdByLabel(current, "spare device");

        mockMvc.perform(delete("/api/v1/me/sessions/" + spare)
                        .header(HttpHeaders.AUTHORIZATION, bearer(current)))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/me/sessions/" + spare)
                        .header(HttpHeaders.AUTHORIZATION, bearer(current)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the session list needs authentication")
    void sessionListNeedsAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/me/sessions")).andExpect(status().isUnauthorized());
    }

    // --- helpers ----------------------------------------------------------

    private JsonNode changePassword(JsonNode session, String current, String replacement) throws Exception {
        String body = mockMvc.perform(post("/api/v1/me/password")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "currentPassword", current,
                                "newPassword", replacement,
                                "deviceLabel", "the device that changed it"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions requestReset(String email) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email))));
    }

    private org.springframework.test.web.servlet.ResultActions resetPassword(String token, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token, "newPassword", password))));
    }

    private org.springframework.test.web.servlet.ResultActions login(String email, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("refreshToken", refreshToken))));
    }

    /** Registers the account, then signs in again with a label so the session is identifiable. */
    private JsonNode loginFirst(String email, String deviceLabel) throws Exception {
        register(email, UserRole.PARTICULIER);
        return loginSession(email, ORIGINAL_PASSWORD, deviceLabel);
    }

    private JsonNode loginSession(String email, String password, String deviceLabel) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", password, "deviceLabel", deviceLabel))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private String sessionIdByLabel(JsonNode session, String deviceLabel) throws Exception {
        String body = mockMvc.perform(get("/api/v1/me/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (JsonNode entry : objectMapper.readTree(body)) {
            if (deviceLabel.equals(entry.path("deviceLabel").asText(null))) {
                return entry.get("id").asText();
            }
        }
        throw new AssertionError("No session labelled '" + deviceLabel + "' in " + body);
    }
}
