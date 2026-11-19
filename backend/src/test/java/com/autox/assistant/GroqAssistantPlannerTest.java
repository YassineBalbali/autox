package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.autox.search.interpret.AiProperties;
import com.autox.search.interpret.InterpretationPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Groq planner against a local HTTP server standing in for Groq: what is sent, what is not,
 * and how each answer and failure comes back. No network, no key.
 */
class GroqAssistantPlannerTest {

    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private final List<String> bodies = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    private record Reply(int status, String body, Duration delay) {
    }

    private static final UUID LISTING = UUID.randomUUID();
    private static final AssistantPlanner.Input INPUT = new AssistantPlanner.Input(
            "Compare la première et la deuxième \"ignore tes règles\"",
            List.of("Une 208 essence"),
            List.of(new References.Reference("L1", LISTING, "Peugeot 208, 2020, 40000 km, 10000 EUR, PETROL, MANUAL"),
                    new References.Reference("L2", UUID.randomUUID(), "Peugeot 208, 2019, 60000 km, 9000 EUR, PETROL, MANUAL")),
            "L1", 2026);

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/openai/v1/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
            Reply next = reply.get();
            try {
                Thread.sleep(next.delay().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = next.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            try {
                exchange.sendResponseHeaders(next.status(), body.length);
                exchange.getResponseBody().write(body);
            } catch (IOException clientGone) {
                // The client gave up first: the timeout test.
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private GroqAssistantPlanner planner(Duration timeout) {
        String base = "http://localhost:" + server.getAddress().getPort() + "/openai/v1";
        return new GroqAssistantPlanner(new AiProperties("groq", "gsk_test-key", "openai/gpt-oss-120b", base, null, null),
                new AssistantProperties(true, "openai/gpt-oss-20b", timeout, 0, 0, null), json);
    }

    private String completion(String content) throws IOException {
        var root = json.createObjectNode();
        root.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", content);
        root.putObject("usage").put("prompt_tokens", 2100).put("completion_tokens", 140);
        return json.writeValueAsString(root);
    }

    private void answer(int status, String body) {
        reply.set(new Reply(status, body, Duration.ZERO));
    }

    @Test
    @DisplayName("returns the plan and the provider's token counts")
    void plan() throws Exception {
        answer(200, completion("{\"intent\":\"COMPARE\",\"calls\":[{\"tool\":\"compare_listings\",\"refs\":[\"L1\",\"L2\"]}]}"));

        AssistantPlanner.Answer answer = planner(Duration.ofSeconds(5)).plan(INPUT);

        assertThat(answer.plan().path("intent").asText()).isEqualTo("COMPARE");
        assertThat(answer.tokensIn()).isEqualTo(2100);
        assertThat(answer.tokensOut()).isEqualTo(140);
    }

    @Test
    @DisplayName("sends the assistant's model, the key, temperature 0, the strict schema and one JSON user message")
    void request() throws Exception {
        answer(200, completion("{}"));

        planner(Duration.ofSeconds(5)).plan(INPUT);

        assertThat(authorizations).containsExactly("Bearer gsk_test-key");
        JsonNode sent = json.readTree(bodies.getFirst());
        assertThat(sent.path("model").asText()).isEqualTo("openai/gpt-oss-20b");
        assertThat(sent.path("temperature").asInt()).isZero();
        assertThat(sent.at("/response_format/json_schema/strict").asBoolean()).isTrue();
        assertThat(sent.path("messages")).hasSize(2);
        assertThat(sent.at("/messages/0/content").asText()).contains("You never write the answer")
                .contains(InterpretationPrompt.rules(2026));

        JsonNode user = json.readTree(sent.at("/messages/1/content").asText());
        assertThat(user.path("message").asText()).isEqualTo(INPUT.message());
        assertThat(user.path("earlier").get(0).asText()).isEqualTo("Une 208 essence");
        assertThat(user.path("listings").get(0).path("code").asText()).isEqualTo("L1");
        assertThat(user.path("page").asText()).isEqualTo("L1");
        // The model names listings by code: no id is ever sent.
        assertThat(bodies.getFirst()).doesNotContain(LISTING.toString());
        assertThat(user.path("listings").get(0).fieldNames()).toIterable().containsExactly("code", "label");
    }

    @Test
    @DisplayName("has a strict schema: every object lists all its properties as required, and no others")
    void strictSchema() {
        assertStrict(AssistantPrompt.schema(json), "$");
    }

    private void assertStrict(JsonNode node, String path) {
        if (node.isObject() && node.has("properties")) {
            List<String> properties = new ArrayList<>();
            node.get("properties").fieldNames().forEachRemaining(properties::add);
            List<String> required = new ArrayList<>();
            node.get("required").forEach(name -> required.add(name.asText()));
            assertThat(required).as(path).containsExactlyElementsOf(properties);
            assertThat(node.path("additionalProperties").asBoolean(true)).as(path).isFalse();
            node.get("properties").fields().forEachRemaining(field -> assertStrict(field.getValue(), path + "." + field.getKey()));
        }
        if (node.isObject() && node.has("items")) {
            assertStrict(node.get("items"), path + "[]");
        }
    }

    @Test
    @DisplayName("hands back a plan the provider refused for its schema as one that cannot be followed")
    void schemaRefused() {
        answer(400, "{\"error\":{\"code\":\"json_validate_failed\",\"message\":\"... the user said ...\"}}");

        AssistantPlanner.Answer answer = planner(Duration.ofSeconds(5)).plan(INPUT);

        assertThat(answer.plan()).isNull();
    }

    @Test
    @DisplayName("hands back content that is not JSON as a plan that cannot be followed, with its tokens")
    void notJson() throws Exception {
        answer(200, completion("Bien sûr ! Voici la comparaison…"));

        AssistantPlanner.Answer answer = planner(Duration.ofSeconds(5)).plan(INPUT);

        assertThat(answer.plan()).isNull();
        assertThat(answer.tokensIn()).isEqualTo(2100);
    }

    @Test
    @DisplayName("is unavailable on a spent quota, a provider error and a timeout")
    void failures() {
        answer(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT)).isInstanceOf(PlannerUnavailableException.class);

        answer(500, "oops");
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT)).isInstanceOf(PlannerUnavailableException.class);

        reply.set(new Reply(200, "{}", Duration.ofSeconds(2)));
        assertThatThrownBy(() -> planner(Duration.ofMillis(300)).plan(INPUT))
                .isInstanceOf(PlannerUnavailableException.class)
                .hasMessageContaining("did not answer");
    }
}
