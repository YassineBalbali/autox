package com.autox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** The error contract: one shape, one language, regardless of what the client asks for. */
class ErrorContractIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("validation messages are English even when the client asks for French")
    void validationMessagesAreEnglishRegardlessOfAcceptLanguage() throws Exception {
        Map<String, Object> invalid = Map.of(
                "email", "not-an-email",
                "password", "short",
                "role", "PARTICULIER",
                "displayName", "",
                "countryCode", "FR");

        String french = mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "fr-FR,fr;q=0.9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn().getResponse().getContentAsString();

        String german = mockMvc.perform(post("/api/v1/auth/register")
                        .header(HttpHeaders.ACCEPT_LANGUAGE, "de-DE")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        // Identical bodies apart from the timestamp: the language does not vary.
        assertThat(stripTimestamp(french)).isEqualTo(stripTimestamp(german));

        assertThat(french)
                .contains("must not be blank")
                .contains("must be a well-formed email address")
                .contains("must be between 12 and 128 characters")
                // The French defaults that used to leak through.
                .doesNotContain("ne doit pas être vide")
                .doesNotContain("adresse électronique");
    }

    @Test
    @DisplayName("every error carries code, status, instance and timestamp")
    void errorShapeIsUniform() throws Exception {
        String email = uniqueEmail("shape");
        register(email, UserRole.PARTICULIER);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(registrationPayload(email, UserRole.PARTICULIER))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(jsonPath("$.instance").value("/api/v1/auth/register"))
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    private static String stripTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<fixed>\"");
    }
}
