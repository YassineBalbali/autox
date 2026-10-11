package com.autox.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The committed OpenAPI snapshot the frontend generates its client from.
 *
 * <p>The frontend does not fetch {@code /v3/api-docs} at build time — CI has no backend to
 * ask, a developer should be able to build offline, and a commit ought to record which
 * contract it was built against. It generates from {@code frontend/openapi.json} instead.
 *
 * <p>Which creates exactly one risk: that file going stale. An endpoint changes, nobody
 * refreshes the snapshot, and the frontend compiles happily against a contract the server
 * no longer honours — a drift that type generation was supposed to eliminate and would
 * instead be hiding. <b>This test is what makes the snapshot safe to rely on.</b> It fails
 * the backend build, on the commit that caused it, and says how to fix it.
 */
class OpenApiSnapshotTest extends AbstractIntegrationTest {

    /**
     * Relative to the backend module, which is Maven's working directory for tests. Not
     * configurable on purpose: a path that can be pointed elsewhere is a path that gets
     * pointed at something stale.
     */
    private static final Path SNAPSHOT = Path.of("..", "frontend", "openapi.json");

    private static final String REFRESH = """

            The committed OpenAPI snapshot no longer matches this application.

            Refresh it:
                docker compose up -d && (cd backend && mvn spring-boot:run)
                cd frontend && npm run api:fetch && npm run api:generate

            Then review the diff to openapi.json — it is the API contract changing, which is
            worth reading before it reaches a client.
            """;

    @Test
    @DisplayName("the committed snapshot matches the document this application serves")
    void snapshotIsCurrent() throws Exception {
        assertThat(SNAPSHOT)
                .as("the frontend generates its client from this file" + REFRESH)
                .exists();

        JsonNode live = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        JsonNode committed = objectMapper.readTree(Files.readString(SNAPSHOT));

        if (live.equals(committed)) {
            return;
        }

        // Jackson's own diff is one enormous string. Walking it names the paths that
        // actually changed, which is the difference between a useful failure and a
        // "documents differ" that sends someone to a diff tool.
        List<String> differences = new ArrayList<>();
        describeDifferences("", committed, live, differences);
        assertThat(differences)
                .as("snapshot (expected) versus live document (actual)" + REFRESH)
                .isEmpty();
    }

    @Test
    @DisplayName("no validation predicate is published as a request field")
    void validationPredicatesAreNotRequestFields() throws Exception {
        // @AssertTrue cross-field constraints are bean properties as far as a schema
        // generator is concerned, so `isCompanyNamePresentForPro()` was published as a
        // writable boolean named `companyNamePresentForPro` on RegisterRequest — eleven of
        // them across three DTOs. Harmless to the server, which ignores them on input, and
        // not harmless in a generated client, where they look like fields a caller should
        // set. @Schema(hidden = true) keeps them out; this keeps them out tomorrow.
        JsonNode schemas = objectMapper.readTree(mockMvc.perform(get("/v3/api-docs"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString())
                .get("components").get("schemas");

        List<String> suspicious = new ArrayList<>();
        schemas.fieldNames().forEachRemaining(schemaName -> {
            JsonNode properties = schemas.get(schemaName).get("properties");
            if (properties == null) {
                return;
            }
            properties.fieldNames().forEachRemaining(property -> {
                // The giveaway: a boolean whose name reads as an assertion about the
                // payload rather than a value in it.
                if (property.endsWith("Unambiguous")
                        || property.endsWith("Consistent")
                        || property.endsWith("Complete")
                        || property.endsWith("Identified")
                        || property.endsWith("IdentifiedOnce")
                        || property.endsWith("PresentForPro")
                        || property.endsWith("AssignableRole")) {
                    suspicious.add(schemaName + "." + property);
                }
            });
        });

        assertThat(suspicious)
                .as("add @Schema(hidden = true) beside the @AssertTrue that produced these")
                .isEmpty();
    }

    /** Recursive structural comparison, reporting JSON-pointer-ish paths. */
    private static void describeDifferences(String path, JsonNode expected, JsonNode actual,
                                            List<String> differences) {
        if (differences.size() >= 25) {
            return;
        }
        if (expected.equals(actual)) {
            return;
        }
        if (expected.isObject() && actual.isObject()) {
            java.util.TreeSet<String> names = new java.util.TreeSet<>();
            expected.fieldNames().forEachRemaining(names::add);
            actual.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                JsonNode left = expected.get(name);
                JsonNode right = actual.get(name);
                String child = path + "/" + name;
                if (left == null) {
                    differences.add("added in the live document: " + child);
                } else if (right == null) {
                    differences.add("removed from the live document: " + child);
                } else {
                    describeDifferences(child, left, right, differences);
                }
            }
            return;
        }
        if (expected.isArray() && actual.isArray() && expected.size() == actual.size()) {
            for (int index = 0; index < expected.size(); index++) {
                describeDifferences(path + "[" + index + "]", expected.get(index), actual.get(index),
                        differences);
            }
            return;
        }
        differences.add(path + ": snapshot has " + abbreviate(expected) + ", live has " + abbreviate(actual));
    }

    private static String abbreviate(JsonNode node) {
        String text = node.toString();
        return text.length() <= 80 ? text : text.substring(0, 77) + "...";
    }
}
