package com.autox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.common.Sha256;
import com.autox.mail.OutboxPoller;
import com.autox.support.AbstractIntegrationTest;
import com.autox.support.ListingTestFixtures;
import com.autox.support.RecordingEmailSender;
import com.autox.support.RecordingMail;
import com.autox.user.AppUser;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** The verification link, and what an account cannot do without it. */
@RecordingMail
class EmailVerificationIntegrationTest extends AbstractIntegrationTest {

    private static final String RESEND = "/api/v1/me/email/verification";

    @Autowired RecordingEmailSender emails;
    @Autowired OutboxPoller poller;

    @BeforeEach
    void clearMailbox() {
        emails.clear();
    }

    // ---- the link -------------------------------------------------------------------------

    @Test
    @DisplayName("sign-up emails a link in French, and only its hash is stored")
    void signUpSendsALink() throws Exception {
        String email = uniqueEmail("signup");

        JsonNode session = registerUnverified(email, UserRole.PARTICULIER);
        poller.pollNow();

        assertThat(emails.last().to()).isEqualTo(email);
        assertThat(emails.last().subject()).isEqualTo("Confirmez votre adresse e-mail AutoX");
        assertThat(emails.last().textBody()).contains("https://autox.test/verifier-email?token=").contains("24 heures");
        assertThat(emails.last().htmlBody()).contains("https://autox.test/verifier-email?token=").contains("lang=\"fr\"");
        String token = emails.lastResetToken();
        assertThat(jdbcTemplate.queryForObject("SELECT token_hash FROM email_verification_token", String.class))
                .isEqualTo(Sha256.hex(token))
                .isNotEqualTo(token);
        assertThat(session.at("/user/emailVerified").asBoolean()).isFalse();
        assertThat(session.at("/user/emailVerificationPending").asBoolean()).isTrue();
        assertThat(session.at("/user/status").asText()).isEqualTo("PENDING_VERIFICATION");
    }

    @Test
    @DisplayName("opening the link makes the account ACTIVE and verified, with no sign-in needed")
    void linkVerifies() throws Exception {
        JsonNode session = registerUnverified(uniqueEmail("verify"), UserRole.PARTICULIER);
        String token = lastToken();

        verify(token).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.emailVerificationPending").value(false));
    }

    @Test
    @DisplayName("the link works once")
    void singleUse() throws Exception {
        registerUnverified(uniqueEmail("once"), UserRole.PARTICULIER);
        String token = lastToken();

        verify(token).andExpect(status().isNoContent());

        verify(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    }

    @Test
    @DisplayName("an expired link is refused and verifies nothing")
    void expires() throws Exception {
        JsonNode session = registerUnverified(uniqueEmail("expired"), UserRole.PARTICULIER);
        String token = lastToken();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT expires_at BETWEEN now() + interval '23 hours 59 minutes' AND now() + interval '24 hours' "
                        + "FROM email_verification_token", Boolean.class)).isTrue();
        jdbcTemplate.update("UPDATE email_verification_token SET expires_at = now() - interval '1 second'");

        verify(token).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(jsonPath("$.emailVerified").value(false));
    }

    @Test
    @DisplayName("an unknown link is refused the same way")
    void unknown() throws Exception {
        verify("not-a-token").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_TOKEN"));
    }

    @Test
    @DisplayName("asking again replaces the earlier link")
    void resendSupersedes() throws Exception {
        JsonNode session = registerUnverified(uniqueEmail("resend"), UserRole.PARTICULIER);
        String first = lastToken();

        resend(session).andExpect(status().isAccepted());
        String second = lastToken();

        assertThat(second).isNotEqualTo(first);
        verify(first).andExpect(status().isBadRequest());
        verify(second).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("asking again is rate-limited per account")
    void resendIsThrottled() throws Exception {
        JsonNode session = registerUnverified(uniqueEmail("flood"), UserRole.PARTICULIER);

        for (int attempt = 0; attempt < 3; attempt++) {
            resend(session).andExpect(status().isAccepted());
        }
        long written = outboxCount();

        resend(session).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));

        // The refusal is counted (a rolled-back count would never reach the cap) and writes nothing.
        assertThat(outboxCount()).isEqualTo(written);
        resend(session).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("asking again with a verified address sends nothing")
    void resendWhenVerified() throws Exception {
        JsonNode session = registerUnverified(uniqueEmail("done"), UserRole.PARTICULIER);
        verify(lastToken()).andExpect(status().isNoContent());
        long written = outboxCount();

        resend(session).andExpect(status().isAccepted());

        assertThat(outboxCount()).isEqualTo(written);
    }

    @Test
    @DisplayName("asking again needs an account")
    void resendNeedsAuthentication() throws Exception {
        mockMvc.perform(post(RESEND)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a suspended account's address can be verified, but the suspension stays until lifted")
    void suspendedAccount() throws Exception {
        String email = uniqueEmail("suspended");
        registerUnverified(email, UserRole.PARTICULIER);
        String token = lastToken();
        jdbcTemplate.update("UPDATE app_user SET status = 'SUSPENDED' WHERE email = ?", email);

        verify(token).andExpect(status().isNoContent());

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM app_user WHERE email = ?", String.class, email))
                .isEqualTo("SUSPENDED");
    }

    // ---- what it holds back ---------------------------------------------------------------

    @Test
    @DisplayName("unverified: a draft can be saved, but it cannot be published until the address is verified")
    void publishingIsBlocked() throws Exception {
        JsonNode session = registerUnverified(uniqueEmail("seller"), UserRole.PARTICULIER);
        String listing = draft(session);
        attachReadyPhoto(listing);

        publish(session, listing).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM listing WHERE id = ?::uuid", String.class, listing))
                .isEqualTo("DRAFT");

        verify(lastToken()).andExpect(status().isNoContent());

        // The token in hand was issued before the click, and still says unverified: the server
        // asks the account, not the token.
        publish(session, listing).andExpect(status().isOk());
    }

    @Test
    @DisplayName("unverified: no message can be started or replied to, and the inbox still opens")
    void messagingIsBlocked() throws Exception {
        JsonNode seller = register(uniqueEmail("seller"), UserRole.PARTICULIER);
        String listing = published(seller);
        JsonNode buyer = registerUnverified(uniqueEmail("buyer"), UserRole.PARTICULIER);

        contact(buyer, listing).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM message", Long.class)).isZero();

        // Someone who wrote before verification existed, say, and is then held back.
        JsonNode other = register(uniqueEmail("other"), UserRole.PARTICULIER);
        String conversation = json(contact(other, listing).andExpect(status().isOk()).andReturn()).get("id").asText();
        jdbcTemplate.update("UPDATE app_user SET email_verification_required = true WHERE id = ?::uuid",
                other.at("/user/id").asText());

        mockMvc.perform(post("/api/v1/conversations/" + conversation + "/messages")
                        .header(HttpHeaders.AUTHORIZATION, bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("body", "Encore moi"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
        mockMvc.perform(get("/api/v1/conversations").header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isOk());

        verify(lastTokenFromOutbox(buyer)).andExpect(status().isNoContent());
        contact(buyer, listing).andExpect(status().isOk());
    }

    @Test
    @DisplayName("an account from before verification keeps working, and is told it is unverified")
    void existingAccountsKeepWorking() throws Exception {
        // As V22 leaves them: the column false, the status and the verified date as they were.
        JsonNode existing = register(uniqueEmail("existing"), UserRole.PARTICULIER);
        JsonNode buyer = register(uniqueEmail("buyer"), UserRole.PARTICULIER);

        String listing = published(existing);
        contact(buyer, listing).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(existing)))
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.emailVerificationPending").value(false));

        // It can verify when it likes.
        resend(existing).andExpect(status().isAccepted());
        verify(lastToken()).andExpect(status().isNoContent());
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(existing)))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("an account made outside sign-up, as a demo seller is, is not held back and gets no email")
    void demoAccountsLeftAlone() {
        AppUser seeded = createUser(uniqueEmail("demo"), UserRole.PROFESSIONNEL, UserStatus.PENDING_VERIFICATION);

        assertThat(seeded.isEmailVerificationPending()).isFalse();
        assertThat(outboxCount()).isZero();
    }

    @Test
    @DisplayName("admin pages are not blocked for an unverified administrator")
    void adminPagesNotBlocked() throws Exception {
        String email = uniqueEmail("admin");
        registerUnverified(email, UserRole.PARTICULIER);
        jdbcTemplate.update("UPDATE app_user SET role = 'ADMIN' WHERE email = ?", email);
        JsonNode session = json(postJson("/api/v1/auth/login",
                Map.of("email", email, "password", "correct-horse-battery")));

        assertThat(session.at("/user/emailVerificationPending").asBoolean()).isTrue();
        mockMvc.perform(get("/api/v1/admin/stats").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk());
    }

    // ---- helpers --------------------------------------------------------------------------

    private String lastToken() {
        poller.pollNow();
        return emails.lastResetToken();
    }

    /** The link this account was sent, for a test where another account's email came later. */
    private String lastTokenFromOutbox(JsonNode session) {
        poller.pollNow();
        String address = session.at("/user/email").asText();
        var message = emails.sent().stream().filter(sent -> sent.to().equals(address)).reduce((a, b) -> b).orElseThrow();
        var matcher = java.util.regex.Pattern.compile("[?&]token=([^\\s&]+)").matcher(message.textBody());
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    private long outboxCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM outbox_email", Long.class);
    }

    private ResultActions verify(String token) throws Exception {
        return mockMvc.perform(post("/api/v1/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("token", token))));
    }

    private ResultActions resend(JsonNode session) throws Exception {
        return mockMvc.perform(post(RESEND).header(HttpHeaders.AUTHORIZATION, bearer(session)));
    }

    private String draft(JsonNode session) throws Exception {
        return json(mockMvc.perform(post("/api/v1/listings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ListingTestFixtures.freeTextListingPayload())))
                .andExpect(status().isCreated())
                .andReturn()).get("id").asText();
    }

    private ResultActions publish(JsonNode session, String listing) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listing + "/publish")
                .header(HttpHeaders.AUTHORIZATION, bearer(session)));
    }

    private String published(JsonNode session) throws Exception {
        String listing = draft(session);
        attachReadyPhoto(listing);
        publish(session, listing).andExpect(status().isOk());
        return listing;
    }

    private ResultActions contact(JsonNode session, String listing) throws Exception {
        return mockMvc.perform(post("/api/v1/listings/" + listing + "/conversation")
                .header(HttpHeaders.AUTHORIZATION, bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("body", "Bonjour"))));
    }
}
