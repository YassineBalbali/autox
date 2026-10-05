package com.autox.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Own-profile access and role-based authorisation. */
class ProfileAndAuthorizationIntegrationTest extends AbstractIntegrationTest {

    // --- authentication of protected endpoints ----------------------------

    @Test
    @DisplayName("a protected endpoint without a token returns problem+json, not an HTML error page")
    void missingTokenIsRejectedWithProblemDetail() throws Exception {
        mockMvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content -> assertThat(content.getResponse().getContentType())
                        .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.instance").value("/api/v1/me"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    @DisplayName("a forged or corrupted token is rejected")
    void forgedTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    @DisplayName("a token signed with the wrong key is rejected")
    void tokenSignedWithAnotherKeyIsRejected() throws Exception {
        // Valid structure, valid claims, wrong signature.
        String foreign = "eyJhbGciOiJIUzI1NiJ9."
                + "eyJpc3MiOiJodHRwczovL2FwaS5hdXRveC50ZXN0Iiwic3ViIjoiMDE4ZjAwMDAtMDAwMC03MDAwLTgwMDAtMDAwMDAwMDAwMDAxIn0."
                + "ZmFrZS1zaWduYXR1cmUtdGhhdC13aWxsLW5vdC12ZXJpZnk";

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + foreign))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    // --- GET /me ----------------------------------------------------------

    @Test
    @DisplayName("GET /me returns the caller's own profile and never the password hash")
    void getOwnProfile() throws Exception {
        String email = uniqueEmail("profile");
        JsonNode session = register(email, UserRole.PARTICULIER);

        String body = mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("PARTICULIER"))
                .andExpect(jsonPath("$.countryCode").value("FR"))
                .andExpect(jsonPath("$.locale").value("fr-FR"))
                .andExpect(jsonPath("$.emailVerified").value(false))
                .andExpect(jsonPath("$.professional").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("passwordHash").doesNotContain("$2a$").doesNotContain("$2b$");
    }

    @Test
    @DisplayName("a dealer's profile includes the company block")
    void dealerProfileIncludesCompany() throws Exception {
        JsonNode session = register(uniqueEmail("pro-profile"), UserRole.PROFESSIONNEL);

        mockMvc.perform(get("/api/v1/me").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professional.companyName").value("Garage du Test"));
    }

    @Test
    @DisplayName("one user's token cannot read another user's profile — /me is always the caller")
    void profileIsScopedToTheCaller() throws Exception {
        JsonNode alice = register(uniqueEmail("alice"), UserRole.PARTICULIER);
        JsonNode bob = register(uniqueEmail("bob"), UserRole.PARTICULIER);

        String aliceId = alice.get("user").get("id").asText();
        String bobSeesId = json(mockMvc.perform(get("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(bob)))
                .andExpect(status().isOk())
                .andReturn()).get("id").asText();

        assertThat(bobSeesId).isNotEqualTo(aliceId);
    }

    // --- PATCH /me --------------------------------------------------------

    @Test
    @DisplayName("PATCH /me updates only the fields supplied")
    void patchUpdatesSuppliedFieldsOnly() throws Exception {
        String email = uniqueEmail("patch");
        JsonNode session = register(email, UserRole.PARTICULIER);

        mockMvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "displayName", "Yassine B.",
                                "phone", "+33612345678"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Yassine B."))
                .andExpect(jsonPath("$.phone").value("+33612345678"))
                // Untouched fields keep their values.
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.locale").value("fr-FR"));

        AppUser stored = users.findActiveByEmail(email).orElseThrow();
        assertThat(stored.getDisplayName()).isEqualTo("Yassine B.");
        assertThat(stored.getPhoneE164()).isEqualTo("+33612345678");
    }

    @Test
    @DisplayName("PATCH /me rejects a badly formatted phone number with a field error")
    void patchRejectsInvalidPhone() throws Exception {
        JsonNode session = register(uniqueEmail("badphone"), UserRole.PARTICULIER);

        mockMvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("phone", "0612345678"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[?(@.field == 'phone')]").exists());
    }

    @Test
    @DisplayName("PATCH /me cannot change the email, the role or the password")
    void patchCannotEscalateOrChangeIdentity() throws Exception {
        String email = uniqueEmail("escalate");
        JsonNode session = register(email, UserRole.PARTICULIER);

        Map<String, Object> hostile = new HashMap<>();
        hostile.put("email", "attacker@evil.test");
        hostile.put("role", "ADMIN");
        hostile.put("password", "new-password-attempt");
        hostile.put("status", "ACTIVE");
        hostile.put("displayName", "Still Me");

        mockMvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(hostile)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Still Me"))
                // Unknown members are ignored, not applied.
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("PARTICULIER"))
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"));

        AppUser stored = users.findActiveByEmail(email).orElseThrow();
        assertThat(stored.getRole()).isEqualTo(UserRole.PARTICULIER);
        assertThat(passwordEncoder.matches("correct-horse-battery", stored.getPasswordHash())).isTrue();
    }

    @Test
    @DisplayName("a private individual cannot attach a company profile")
    void particulierCannotSetCompanyProfile() throws Exception {
        JsonNode session = register(uniqueEmail("not-a-dealer"), UserRole.PARTICULIER);

        mockMvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "professional", Map.of("companyName", "Fake Garage")))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a dealer can update its company details")
    void dealerUpdatesCompanyDetails() throws Exception {
        JsonNode session = register(uniqueEmail("dealer-update"), UserRole.PROFESSIONNEL);

        mockMvc.perform(patch("/api/v1/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "professional", Map.of(
                                        "companyName", "Garage Bellevue",
                                        "registrationNumber", "81234567800017",
                                        "websiteUrl", "https://garage-bellevue.fr")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.professional.companyName").value("Garage Bellevue"))
                .andExpect(jsonPath("$.professional.registrationNumber").value("81234567800017"));
    }

    // --- role-based authorisation -----------------------------------------

    @Test
    @DisplayName("a private individual is refused the admin endpoint")
    void particulierCannotReachAdminEndpoint() throws Exception {
        JsonNode session = register(uniqueEmail("nosy"), UserRole.PARTICULIER);

        mockMvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("a dealer is also refused the admin endpoint")
    void professionnelCannotReachAdminEndpoint() throws Exception {
        JsonNode session = register(uniqueEmail("nosy-pro"), UserRole.PROFESSIONNEL);

        mockMvc.perform(get("/api/v1/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an admin gets a paginated user list")
    void adminGetsPaginatedUsers() throws Exception {
        createUser(uniqueEmail("admin"), UserRole.ADMIN, UserStatus.ACTIVE);
        register(uniqueEmail("subject-one"), UserRole.PARTICULIER);
        register(uniqueEmail("subject-two"), UserRole.PROFESSIONNEL);

        JsonNode adminSession = json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", users.findAllActiveByRole(UserRole.ADMIN,
                                                org.springframework.data.domain.PageRequest.of(0, 1))
                                        .getContent().getFirst().getEmail(),
                                "password", "correct-horse-battery"))))
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("page", "0")
                        .param("size", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.hasNext").value(true));
    }

    @Test
    @DisplayName("the admin list can be filtered by role")
    void adminCanFilterByRole() throws Exception {
        createUser(uniqueEmail("admin-filter"), UserRole.ADMIN, UserStatus.ACTIVE);
        register(uniqueEmail("one-dealer"), UserRole.PROFESSIONNEL);
        register(uniqueEmail("one-person"), UserRole.PARTICULIER);

        JsonNode adminSession = loginAsOnlyAdmin();

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("role", "PROFESSIONNEL")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminSession)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].role").value("PROFESSIONNEL"));
    }

    @Test
    @DisplayName("an oversized page request is refused rather than silently served")
    void pageSizeIsCapped() throws Exception {
        createUser(uniqueEmail("admin-cap"), UserRole.ADMIN, UserStatus.ACTIVE);
        JsonNode adminSession = loginAsOnlyAdmin();

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("size", "5000")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminSession)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private JsonNode loginAsOnlyAdmin() throws Exception {
        String adminEmail = users.findAllActiveByRole(UserRole.ADMIN,
                        org.springframework.data.domain.PageRequest.of(0, 1))
                .getContent().getFirst().getEmail();

        return json(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Collections.unmodifiableMap(Map.of(
                                        "email", adminEmail,
                                        "password", "correct-horse-battery")))))
                .andExpect(status().isOk())
                .andReturn());
    }
}
