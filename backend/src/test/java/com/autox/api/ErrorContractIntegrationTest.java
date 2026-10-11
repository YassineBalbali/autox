package com.autox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.api.error.ApiErrorResponse;
import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
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

    @Test
    @DisplayName("the documented error schema matches the errors the handler actually produces")
    void documentedErrorSchemaMatchesReality() throws Exception {
        // ApiErrorResponse is a parallel description of a body built at runtime from
        // ProblemDetail plus setProperty calls, so the compiler cannot keep the two in step.
        // This is what does. Both directions are checked: a member added to the record must
        // be listed here, and a member the handler starts emitting must be in the record.
        Set<String> documented = Arrays.stream(ApiErrorResponse.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .collect(Collectors.toCollection(TreeSet::new));

        // Grouped by when each member appears rather than alphabetically, because that is
        // the part worth reading; hence in-any-order.
        assertThat(documented).containsExactlyInAnyOrder(
                // always present
                "code", "detail", "instance", "status", "timestamp", "title", "type",
                // conditional: validation failures only
                "errors",
                // conditional: throttled responses only
                "retryAfterSeconds",
                // conditional: 500 only, and the one member no test can trigger on purpose
                "incidentId");

        Set<String> observed = new TreeSet<>();
        observed.addAll(keysOf(validationFailure()));
        observed.addAll(keysOf(conflict()));
        observed.addAll(keysOf(throttled()));

        assertThat(observed)
                .as("every member a real error response carries must be documented")
                .isSubsetOf(documented);
        // The sampled errors between them must cover everything except the 500-only member,
        // so a documented member that the API never actually sends is caught too.
        assertThat(documented).containsAll(observed);
        assertThat(Sets.difference(documented, observed)).containsExactly("incidentId");
    }

    /** 400 with a flat {@code errors} array. */
    private JsonNode validationFailure() throws Exception {
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "not-an-email"))))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString());
    }

    /** 409, which carries no conditional members at all. */
    private JsonNode conflict() throws Exception {
        String email = uniqueEmail("contract-conflict");
        register(email, UserRole.PARTICULIER);
        return objectMapper.readTree(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                registrationPayload(email, UserRole.PARTICULIER))))
                .andExpect(status().isConflict())
                .andReturn().getResponse().getContentAsString());
    }

    /** 429 with {@code retryAfterSeconds}, via the login throttle. */
    private JsonNode throttled() throws Exception {
        String email = uniqueEmail("contract-throttle");
        register(email, UserRole.PARTICULIER);

        // The test profile allows four failures before a delay applies; the fifth is refused.
        String body = null;
        for (int attempt = 0; attempt < 8; attempt++) {
            var result = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    Map.of("email", email, "password", "definitely-not-the-password"))))
                    .andReturn();
            if (result.getResponse().getStatus() == 429) {
                body = result.getResponse().getContentAsString();
                break;
            }
        }

        assertThat(body).as("the login throttle should refuse within eight attempts").isNotNull();
        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("retryAfterSeconds").asLong()).isPositive();
        return problem;
    }

    private static Set<String> keysOf(JsonNode body) {
        Set<String> keys = new TreeSet<>();
        body.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    /** Tiny helper, so the assertion above reads as the set operation it is. */
    private static final class Sets {
        static Set<String> difference(Set<String> left, Set<String> right) {
            Set<String> result = new TreeSet<>(left);
            result.removeAll(right);
            return result;
        }
    }

    private static String stripTimestamp(String body) {
        return body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"<fixed>\"");
    }
}
