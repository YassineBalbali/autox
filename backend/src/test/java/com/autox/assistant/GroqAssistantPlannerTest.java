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

    private record Reply(int status, String body, Duration delay, java.util.Map<String, String> headers) {
        Reply(int status, String body, Duration delay) {
            this(status, body, delay, java.util.Map.of());
        }
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
            next.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
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
    @DisplayName("sends the assistant's model, the key, temperature 0, JSON mode and one JSON user message")
    void request() throws Exception {
        answer(200, completion("{}"));

        planner(Duration.ofSeconds(5)).plan(INPUT);

        assertThat(authorizations).containsExactly("Bearer gsk_test-key");
        JsonNode sent = json.readTree(bodies.getFirst());
        assertThat(sent.path("model").asText()).isEqualTo("openai/gpt-oss-20b");
        assertThat(sent.path("temperature").asInt()).isZero();
        // JSON mode, no schema: the schema cost ~1 000 prompt tokens and refusals (docs/assistant-plan.md, 12).
        assertThat(sent.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(sent.at("/response_format/json_schema").isMissingNode()).isTrue();
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
    @DisplayName("describes every intent, tool, reason and question the validator accepts, and nothing else")
    void promptMatchesTheValidator() {
        String prompt = AssistantPrompt.system(2026);
        for (Plan.Intent intent : Plan.Intent.values()) {
            assertThat(prompt).as(intent.name()).containsPattern("(?m)^" + intent.name() + "[ :]").doesNotContain("FINANCING_TERMS");
        }
        for (Plan.Tool tool : Plan.Tool.values()) {
            assertThat(prompt).contains(tool.wireName());
        }
        Plan.PLANNER_REASONS.forEach(reason -> assertThat(prompt).contains(reason.name()));
        for (Plan.Question question : Plan.Question.values()) {
            assertThat(prompt).contains(question.name());
        }
    }

    @Test
    @DisplayName("keeps the assistant's own rules under 2 400 characters, about 550 tokens: every token is quota")
    void promptSize() {
        String prompt = AssistantPrompt.system(2026);
        String own = prompt.substring(0, prompt.indexOf(InterpretationPrompt.rules(2026)));
        assertThat(own.length()).isLessThan(2_400);
    }

    @Test
    @DisplayName("hands back an answer the provider refused as not JSON as a plan that cannot be followed")
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
    @DisplayName("is busy on Groq's 429, for as long as its Retry-After, its message or its reset header says")
    void busy() {
        reply.set(new Reply(429, "{\"error\":{\"code\":\"rate_limit_exceeded\"}}", Duration.ZERO, java.util.Map.of("retry-after", "7")));
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT))
                .isInstanceOfSatisfying(PlannerBusyException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(7));

        answer(429, "{\"error\":{\"code\":\"rate_limit_exceeded\",\"message\":\"Rate limit reached on tokens per minute (TPM): "
                + "Limit 8000, Used 7000, Requested 2900. Please try again in 14.25s. Need more tokens?\"}}");
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT))
                .isInstanceOfSatisfying(PlannerBusyException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(15));

        reply.set(new Reply(429, "{}", Duration.ZERO, java.util.Map.of("x-ratelimit-reset-tokens", "1m2.5s")));
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT))
                .isInstanceOfSatisfying(PlannerBusyException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(63));

        answer(429, "{}");
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT))
                .isInstanceOfSatisfying(PlannerBusyException.class,
                        e -> assertThat(e.retryAfterSeconds()).isEqualTo(GroqAssistantPlanner.DEFAULT_RETRY_SECONDS));
    }

    @Test
    @DisplayName("reads Groq's durations as whole seconds, rounded up")
    void durations() {
        assertThat(GroqAssistantPlanner.durationSeconds("17")).isEqualTo(17);
        assertThat(GroqAssistantPlanner.durationSeconds("7.66s")).isEqualTo(8);
        assertThat(GroqAssistantPlanner.durationSeconds("1m3.5s")).isEqualTo(64);
        assertThat(GroqAssistantPlanner.durationSeconds("2h1m")).isEqualTo(7260);
        assertThat(GroqAssistantPlanner.durationSeconds("620ms")).isEqualTo(1);
        assertThat(GroqAssistantPlanner.durationSeconds("soon")).isNull();
        assertThat(GroqAssistantPlanner.durationSeconds(null)).isNull();
    }

    @Test
    @DisplayName("is unavailable on a provider error and a timeout")
    void failures() {

        answer(500, "oops");
        assertThatThrownBy(() -> planner(Duration.ofSeconds(5)).plan(INPUT)).isInstanceOf(PlannerUnavailableException.class);

        reply.set(new Reply(200, "{}", Duration.ofSeconds(2)));
        assertThatThrownBy(() -> planner(Duration.ofMillis(300)).plan(INPUT))
                .isInstanceOf(PlannerUnavailableException.class)
                .hasMessageContaining("did not answer");
    }
}
