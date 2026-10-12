package com.autox.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.AppUser;
import com.autox.user.ProProfileRepository;
import com.autox.user.RefreshTokenRepository;
import com.autox.user.UserRole;
import com.autox.user.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** Register → login → refresh → logout, including the ways each is meant to fail. */
class AuthFlowIntegrationTest extends AbstractIntegrationTest {

    @Autowired RefreshTokenRepository refreshTokens;
    @Autowired ProProfileRepository proProfiles;

    // --- registration -----------------------------------------------------

    @Test
    @DisplayName("registering a private individual returns 201 with a token pair and stores a bcrypt hash")
    void registerParticulier() throws Exception {
        String email = uniqueEmail("particulier");

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationPayload(email, UserRole.PARTICULIER))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andExpect(jsonPath("$.expiresInSeconds").value(900))
                .andExpect(jsonPath("$.user.role").value("PARTICULIER"))
                .andExpect(jsonPath("$.user.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.user.emailVerified").value(false))
                // The password must never appear anywhere in a response.
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andReturn();

        AppUser stored = users.findActiveByEmail(email).orElseThrow();
        assertThat(stored.getPasswordHash()).startsWith("$2"); // bcrypt
        assertThat(stored.getPasswordHash()).doesNotContain("correct-horse-battery");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("correct-horse-battery");
        assertThat(refreshTokens.countActiveSessions(stored.getId(), java.time.Instant.now())).isEqualTo(1);
    }

    @Test
    @DisplayName("registering a dealer creates the company profile")
    void registerProfessionnel() throws Exception {
        String email = uniqueEmail("pro");
        JsonNode body = register(email, UserRole.PROFESSIONNEL);

        assertThat(body.get("user").get("role").asText()).isEqualTo("PROFESSIONNEL");
        AppUser stored = users.findActiveByEmail(email).orElseThrow();
        assertThat(proProfiles.findByUserId(stored.getId())).isPresent().get()
                .satisfies(profile -> {
                    assertThat(profile.getCompanyName()).isEqualTo("Garage du Test");
                    // Captured at registration rather than left for a later profile edit:
                    // a dealer account with no registry identifier cannot be verified.
                    assertThat(profile.getRegistrationNumber()).isEqualTo("81234567800012");
                });
    }

    @Test
    @DisplayName("a dealer registration without a company name is rejected with a field error")
    void registerProfessionnelWithoutCompanyName() throws Exception {
        Map<String, Object> payload = registrationPayload(uniqueEmail("pro-nocompany"), UserRole.PROFESSIONNEL);
        payload.remove("companyName");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty());
    }

    @Test
    @DisplayName("a dealer registration without a registration number is rejected with a field error")
    void registerProfessionnelWithoutRegistrationNumber() throws Exception {
        Map<String, Object> payload = registrationPayload(uniqueEmail("pro-nosiret"), UserRole.PROFESSIONNEL);
        payload.remove("registrationNumber");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0].field").isNotEmpty());

        // And nothing was created: the whole registration is one transaction, so a rejected
        // dealer must not leave an account behind without its company row.
        assertThat(users.existsByEmailIgnoreCase((String) payload.get("email"))).isFalse();
    }

    @Test
    @DisplayName("nobody can register themselves as ADMIN")
    void adminRoleCannotBeSelfAssigned() throws Exception {
        Map<String, Object> payload = registrationPayload(uniqueEmail("wannabe-admin"), UserRole.PARTICULIER);
        payload.put("role", "ADMIN");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(users.existsByEmailIgnoreCase((String) payload.get("email"))).isFalse();
    }

    @Test
    @DisplayName("the same email cannot register twice, regardless of case")
    void duplicateEmailIsRejectedCaseInsensitively() throws Exception {
        String email = uniqueEmail("dup");
        register(email, UserRole.PARTICULIER);

        Map<String, Object> payload = registrationPayload(email.toUpperCase(), UserRole.PARTICULIER);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
    }

    @Test
    @DisplayName("a country that is not an open market is rejected")
    void disabledCountryIsRejected() throws Exception {
        Map<String, Object> payload = registrationPayload(uniqueEmail("german"), UserRole.PARTICULIER);
        payload.put("countryCode", "DE"); // seeded, but enabled = false

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("a short password is rejected before it reaches the database")
    void shortPasswordIsRejected() throws Exception {
        Map<String, Object> payload = registrationPayload(uniqueEmail("weak"), UserRole.PARTICULIER);
        payload.put("password", "short");

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'password')]").exists());
    }

    @Test
    @DisplayName("malformed JSON returns the standard error shape, not a stack trace")
    void malformedJsonIsHandled() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value("Request body is missing or malformed."));
    }

    // --- login ------------------------------------------------------------

    @Test
    @DisplayName("login with correct credentials returns a new token pair and records the login time")
    void loginSucceeds() throws Exception {
        String email = uniqueEmail("login");
        register(email, UserRole.PARTICULIER);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email,
                                "password", "correct-horse-battery",
                                "deviceLabel", "Pixel 8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());

        assertThat(users.findActiveByEmail(email).orElseThrow().getLastLoginAt()).isNotNull();
    }

    @Test
    @DisplayName("a wrong password and an unknown email are indistinguishable to the caller")
    void loginFailuresAreIndistinguishable() throws Exception {
        String email = uniqueEmail("timing");
        register(email, UserRole.PARTICULIER);

        String wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", "not-the-right-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        String unknownEmail = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", uniqueEmail("ghost"), "password", "correct-horse-battery"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();

        // Same code, same wording: nothing here reveals whether the account exists.
        assertThat(detailOf(wrongPassword)).isEqualTo(detailOf(unknownEmail));
    }

    @Test
    @DisplayName("a suspended account cannot log in, and is told why")
    void suspendedAccountCannotLogIn() throws Exception {
        AppUser suspended = createUser(uniqueEmail("suspended"), UserRole.PARTICULIER, UserStatus.SUSPENDED);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", suspended.getEmail(), "password", "correct-horse-battery"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    @Test
    @DisplayName("an unverified email can still log in — verification gates publishing, not access")
    void unverifiedEmailCanLogIn() throws Exception {
        String email = uniqueEmail("unverified");
        register(email, UserRole.PARTICULIER);

        assertThat(users.findActiveByEmail(email).orElseThrow().getStatus())
                .isEqualTo(UserStatus.PENDING_VERIFICATION);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("email", email, "password", "correct-horse-battery"))))
                .andExpect(status().isOk());
    }

    // --- refresh ----------------------------------------------------------

    @Test
    @DisplayName("refreshing rotates the token: a new pair is issued and the old token stops working")
    void refreshRotatesTheToken() throws Exception {
        JsonNode session = register(uniqueEmail("rotate"), UserRole.PARTICULIER);
        String first = session.get("refreshToken").asText();

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", first))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty())
                .andReturn();

        String second = json(refreshed).get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);

        // The successor works.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", second))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("replaying a rotated refresh token kills the whole family")
    void replayDetectionRevokesTheFamily() throws Exception {
        JsonNode session = register(uniqueEmail("replay"), UserRole.PARTICULIER);
        String stolen = session.get("refreshToken").asText();

        String legitimate = json(mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", stolen))))
                .andExpect(status().isOk())
                .andReturn()).get("refreshToken").asText();

        // An attacker replays the token they copied before rotation.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", stolen))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));

        // The legitimate successor is collateral damage, by design: the session is burned.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", legitimate))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("an unknown refresh token is rejected without revealing anything")
    void unknownRefreshTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("refreshToken", "not-a-real-token"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("a suspended account's refresh token stops working immediately")
    void suspensionInvalidatesRefresh() throws Exception {
        String email = uniqueEmail("suspend-later");
        JsonNode session = register(email, UserRole.PARTICULIER);

        AppUser user = users.findActiveByEmail(email).orElseThrow();
        user.setStatus(UserStatus.SUSPENDED);
        users.saveAndFlush(user);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", session.get("refreshToken").asText()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_SUSPENDED"));
    }

    // --- logout -----------------------------------------------------------

    @Test
    @DisplayName("logout revokes this session only, and is idempotent")
    void logoutRevokesTheSession() throws Exception {
        String email = uniqueEmail("logout");
        JsonNode phone = register(email, UserRole.PARTICULIER);

        // A second session for the same account, as if from a laptop.
        JsonNode laptop = json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", email, "password", "correct-horse-battery", "deviceLabel", "Laptop"))))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", phone.get("refreshToken").asText()))))
                .andExpect(status().isNoContent());

        // Repeating it is still 204 — no information about which tokens exist.
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", phone.get("refreshToken").asText()))))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", phone.get("refreshToken").asText()))))
                .andExpect(status().isUnauthorized());

        // The laptop is untouched.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("refreshToken", laptop.get("refreshToken").asText()))))
                .andExpect(status().isOk());
    }

    private String detailOf(String responseBody) throws Exception {
        return objectMapper.readTree(responseBody).get("detail").asText();
    }
}
