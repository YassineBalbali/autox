package com.autox.assistant;

import com.autox.search.interpret.AiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The planner on Groq: the smart search's provider and key ({@code autox.ai.*}), the assistant's own
 * model and timeout ({@code autox.assistant.*}). Structured output in strict mode, temperature 0,
 * reasoning effort low and left out of the response, as the smart search's client.
 *
 * <p>Every failure is a {@link PlannerUnavailableException}, and the turn a 503, except one: an
 * answer the provider itself refused for not matching the schema ({@code json_validate_failed}) is
 * a plan that cannot be followed, which the turn may ask for once more.
 *
 * <p>Nothing from the provider's error bodies is logged but their machine-readable code: their
 * message can quote the model's output, which is made from the user's text.
 */
@Component
@ConditionalOnProperty(name = "autox.ai.provider", havingValue = AiProperties.GROQ)
public class GroqAssistantPlanner implements AssistantPlanner {

    private static final Logger log = LoggerFactory.getLogger(GroqAssistantPlanner.class);

    /** Reasoning tokens count here too; the plan itself is a few hundred. */
    static final int MAX_COMPLETION_TOKENS = 1024;

    private final AssistantProperties assistant;
    private final ObjectMapper json;
    private final RestClient client;

    public GroqAssistantPlanner(AiProperties ai, AssistantProperties assistant, ObjectMapper json) {
        this.assistant = assistant;
        this.json = json;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(assistant.timeout()).build());
        requests.setReadTimeout(assistant.timeout());
        this.client = RestClient.builder()
                .baseUrl(ai.baseUrl())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ai.apiKey())
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Answer plan(Input input) {
        String answer;
        try {
            answer = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request(input))
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException e) {
            String code = errorCode(e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 429) {
                long wait = retryAfter(e);
                log.warn("Assistant planner: Groq is rate limiting ({}), retry in {}s", code.isEmpty() ? "429" : code, wait);
                throw new PlannerBusyException(wait);
            }
            if (e.getStatusCode().value() == 400 && code.equals("json_validate_failed")) {
                log.info("Assistant planner: the provider refused an answer that was not JSON");
                return new Answer(null, 0, 0);
            }
            throw unavailable("Groq answered " + e.getStatusCode().value() + (code.isEmpty() ? "" : " (" + code + ")"), e);
        } catch (ResourceAccessException e) {
            throw unavailable(causedBy(e, HttpTimeoutException.class)
                    ? "Groq did not answer within " + assistant.timeout() : "Groq could not be reached", e);
        } catch (RestClientException e) {
            throw unavailable("Groq call failed", e);
        }
        return read(answer);
    }

    /** The request body. Package-private so a test can assert what is, and is not, sent. */
    ObjectNode request(Input input) {
        ObjectNode body = json.createObjectNode()
                .put("model", assistant.model())
                .put("temperature", 0)
                .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
                .put("reasoning_effort", "low")
                .put("include_reasoning", false);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", AssistantPrompt.system(input.currentYear()));
        messages.addObject().put("role", "user").put("content", AssistantPrompt.user(input, json));
        // JSON mode: valid JSON, its shape described in the prompt and checked by PlanValidator.
        body.putObject("response_format").put("type", "json_object");
        return body;
    }

    private Answer read(String answer) {
        try {
            JsonNode completion = json.readTree(answer == null ? "" : answer);
            long tokensIn = completion.path("usage").path("prompt_tokens").asLong(0);
            long tokensOut = completion.path("usage").path("completion_tokens").asLong(0);
            JsonNode content = completion.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw unavailable("Groq answered without message content", null, tokensIn + tokensOut);
            }
            JsonNode plan;
            try {
                plan = json.readTree(content.asText());
            } catch (JsonProcessingException e) {
                // Not JSON at all: a plan that cannot be followed, which the turn may ask for again.
                plan = null;
            }
            return new Answer(plan, tokensIn, tokensOut);
        } catch (JsonProcessingException e) {
            throw unavailable("Groq's answer is not JSON", e);
        }
    }

    /** When Groq says it can be asked again: whole seconds, rounded up; 10 when it does not say. */
    long retryAfter(HttpStatusCodeException e) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers != null) {
            Long seconds = durationSeconds(headers.getFirst(HttpHeaders.RETRY_AFTER));
            if (seconds != null) {
                return seconds;
            }
        }
        try {
            String message = json.readTree(e.getResponseBodyAsString()).path("error").path("message").asText("");
            java.util.regex.Matcher hint = TRY_AGAIN.matcher(message);
            if (hint.find()) {
                Long seconds = durationSeconds(hint.group(1));
                if (seconds != null) {
                    return seconds;
                }
            }
        } catch (JsonProcessingException ignored) {
            // No body to read: the default below.
        }
        if (headers != null) {
            Long seconds = durationSeconds(headers.getFirst("x-ratelimit-reset-tokens"));
            if (seconds != null) {
                return seconds;
            }
        }
        return DEFAULT_RETRY_SECONDS;
    }

    static final long DEFAULT_RETRY_SECONDS = 10;
    private static final java.util.regex.Pattern TRY_AGAIN =
            java.util.regex.Pattern.compile("try again in ((?:\\d+h)?(?:\\d+m(?!s))?(?:[\\d.]+s|\\d+ms)?)");
    private static final java.util.regex.Pattern DURATION =
            java.util.regex.Pattern.compile("(?:(\\d+)h)?(?:(\\d+)m(?!s))?(?:([\\d.]+)s|(\\d+)ms)?");

    /** "17", "17.25s", "1m3.5s", "2h1m", "620ms" as whole seconds, rounded up, at least 1. */
    static Long durationSeconds(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.strip();
        if (value.matches("[\\d.]+")) {
            return Math.max(1, (long) Math.ceil(Double.parseDouble(value)));
        }
        java.util.regex.Matcher parts = DURATION.matcher(value);
        if (!parts.matches() || value.isEmpty()) {
            return null;
        }
        double seconds = (parts.group(1) == null ? 0 : Long.parseLong(parts.group(1)) * 3600)
                + (parts.group(2) == null ? 0 : Long.parseLong(parts.group(2)) * 60)
                + (parts.group(3) == null ? 0 : Double.parseDouble(parts.group(3)))
                + (parts.group(4) == null ? 0 : Long.parseLong(parts.group(4)) / 1000.0);
        return Math.max(1, (long) Math.ceil(seconds));
    }

    private String errorCode(String body) {
        try {
            JsonNode error = json.readTree(body == null ? "" : body).path("error");
            String code = error.path("code").asText("");
            String known = !code.isEmpty() ? code : error.path("type").asText("");
            return known.matches("[a-z_]{1,64}") ? known : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private static PlannerUnavailableException unavailable(String message, Throwable cause) {
        return unavailable(message, cause, 0);
    }

    private static PlannerUnavailableException unavailable(String message, Throwable cause, long tokens) {
        log.warn("Assistant planner unavailable: {}", message);
        return new PlannerUnavailableException(message, cause, tokens);
    }

    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }
}
