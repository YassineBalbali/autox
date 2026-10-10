package com.autox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The OpenAPI document the application serves.
 *
 * <p>The interesting assertion is coverage: the spec is generated, so it is only worth having
 * if it describes <em>every</em> endpoint. A controller added later with no entry here would
 * mean a client reading the spec cannot see it.
 */
class OpenApiDocumentIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("the document is served, unauthenticated, as valid OpenAPI 3")
    void servedPublicly() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value(org.hamcrest.Matchers.startsWith("3.")))
                .andExpect(jsonPath("$.info.title").value("AutoX API"))
                .andExpect(jsonPath("$.paths").isNotEmpty());
    }

    @Test
    @DisplayName("the YAML form and the Swagger UI are reachable too")
    void alternateFormsAreReachable() throws Exception {
        // /v3/api-docs.yaml is a sibling path, not a child of /v3/api-docs, so a "/**" matcher
        // does not cover it. It was a 401 until a live check caught it.
        mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("bearer authentication is declared as a security scheme")
    void declaresBearerScheme() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"));
    }

    @Test
    @DisplayName("the secured endpoints are marked as requiring it, and the public ones are not")
    void securityIsMarkedPerEndpoint() throws Exception {
        JsonNode paths = document().get("paths");

        assertThat(paths.get("/api/v1/me").get("get").has("security")).isTrue();
        assertThat(paths.get("/api/v1/listings").get("post").has("security")).isTrue();
        // The shop window and the vocabularies must be callable with no token.
        assertThat(paths.get("/api/v1/listings").get("get").has("security")).isFalse();
        assertThat(paths.get("/api/v1/reference/enums").get("get").has("security")).isFalse();
        assertThat(paths.get("/api/v1/auth/login").get("post").has("security")).isFalse();
    }

    @Test
    @DisplayName("every mapped endpoint appears in the document")
    void coversEveryEndpoint() throws Exception {
        JsonNode paths = document().get("paths");

        TreeSet<String> documented = new TreeSet<>();
        paths.fieldNames().forEachRemaining(path ->
                paths.get(path).fieldNames().forEachRemaining(method ->
                        documented.add(method.toUpperCase(java.util.Locale.ROOT) + " " + path)));

        List<String> missing = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> mapping
                : handlerMapping.getHandlerMethods().entrySet()) {

            Class<?> controller = mapping.getValue().getBeanType();
            // springdoc's own controllers serve the document; they are not part of the API.
            if (!controller.getName().startsWith("com.autox")) {
                continue;
            }
            for (String pattern : patternsOf(mapping.getKey())) {
                for (org.springframework.web.bind.annotation.RequestMethod method
                        : mapping.getKey().getMethodsCondition().getMethods()) {

                    String entry = method.name() + " " + pattern;
                    if (!documented.contains(entry)) {
                        missing.add(entry);
                    }
                }
            }
        }

        assertThat(missing).as("endpoints absent from the OpenAPI document").isEmpty();
        assertThat(documented).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    @DisplayName("the Javadoc on the controllers becomes the operation descriptions")
    void javadocBecomesDescriptions() throws Exception {
        JsonNode search = document().get("paths").get("/api/v1/listings").get("get");

        // If the therapi annotation processor is not wired, the spec compiles but loses every
        // explanation — a silent downgrade that this catches.
        assertThat(search.has("summary") || search.has("description")).isTrue();
        String prose = (search.path("summary").asText("") + " " + search.path("description").asText(""))
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(prose).contains("cursor");
    }

    @Test
    @DisplayName("the error contract is described once, at the document level")
    void errorContractIsDocumented() throws Exception {
        String description = document().get("info").get("description").asText()
                .toLowerCase(java.util.Locale.ROOT);

        assertThat(description).contains("problem+json");
        assertThat(description).contains("minor unit");
        assertThat(description).contains("cursor");
    }

    private JsonNode document() throws Exception {
        return objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static List<String> patternsOf(RequestMappingInfo info) {
        if (info.getPathPatternsCondition() != null) {
            return info.getPathPatternsCondition().getPatternValues().stream().toList();
        }
        return info.getPatternValues().stream().toList();
    }
}
