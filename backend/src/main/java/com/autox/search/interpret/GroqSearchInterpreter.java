package com.autox.search.interpret;

import com.autox.search.interpret.InterpreterUnavailableException.Reason;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Year;
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
 * Groq's OpenAI-compatible chat completions, with structured output in strict mode: the
 * provider constrains decoding to {@link InterpretationPrompt#schema}, so the answer is always
 * a JSON object of that shape. Its values are still checked — strict decoding guarantees a
 * shape, not a meaning.
 *
 * <p>Temperature 0, so one query gets one answer and the eval measures something repeatable.
 * Reasoning effort low and reasoning left out of the response: the task is translation, and
 * every reasoning token is latency and free quota spent.
 *
 * <p><b>The timeout is the request's, end to end.</b> Connection and answer each bounded by
 * {@code autox.ai.timeout}; a slow provider becomes a 503 and the ordinary search, never a
 * hanging page.
 */
@Component
@ConditionalOnProperty(name = "autox.ai.provider", havingValue = AiProperties.GROQ)
public class GroqSearchInterpreter implements SearchInterpreter {

    private static final Logger log = LoggerFactory.getLogger(GroqSearchInterpreter.class);

    /** Reasoning tokens count here too; the filter object itself is a few hundred. */
    static final int MAX_COMPLETION_TOKENS = 2048;

    private final AiProperties properties;
    private final ObjectMapper json;
    private final RestClient client;

    public GroqSearchInterpreter(AiProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;

        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        requests.setReadTimeout(properties.timeout());
        this.client = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey())
                .build();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public JsonNode interpret(String query) {
        String answer;
        try {
            answer = client.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request(query))
                    .retrieve()
                    .body(String.class);
        } catch (HttpStatusCodeException e) {
            // The body of a provider error can echo the request; the status says enough.
            if (e.getStatusCode().value() == 429) {
                throw unavailable(Reason.QUOTA, "Groq refused for quota (429)", e);
            }
            throw unavailable(Reason.PROVIDER_ERROR, "Groq answered " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            if (causedBy(e, HttpTimeoutException.class)) {
                throw unavailable(Reason.TIMEOUT, "Groq did not answer within " + properties.timeout(), e);
            }
            throw unavailable(Reason.PROVIDER_ERROR, "Groq could not be reached", e);
        } catch (RestClientException e) {
            throw unavailable(Reason.PROVIDER_ERROR, "Groq call failed", e);
        }
        return content(answer);
    }

    /** The request body. Package-private so a test can assert what is, and is not, sent. */
    ObjectNode request(String query) {
        ObjectNode body = json.createObjectNode()
                .put("model", properties.model())
                .put("temperature", 0)
                .put("max_completion_tokens", MAX_COMPLETION_TOKENS)
                .put("reasoning_effort", "low")
                .put("include_reasoning", false);
        var messages = body.putArray("messages");
        messages.addObject().put("role", "system")
                .put("content", InterpretationPrompt.system(Year.now().getValue()));
        messages.addObject().put("role", "user")
                .put("content", InterpretationPrompt.user(query, json));

        ObjectNode schema = body.putObject("response_format")
                .put("type", "json_schema")
                .putObject("json_schema")
                .put("name", "search_filters")
                .put("strict", true);
        schema.set("schema", InterpretationPrompt.schema(json));
        return body;
    }

    /** The model's JSON object, out of the chat completion around it. */
    private JsonNode content(String answer) {
        try {
            JsonNode completion = json.readTree(answer == null ? "" : answer);
            JsonNode content = completion.path("choices").path(0).path("message").path("content");
            if (!content.isTextual()) {
                throw unavailable(Reason.PROVIDER_ERROR, "Groq answered without message content", null);
            }
            JsonNode filters = json.readTree(content.asText());
            if (filters == null || !filters.isObject()) {
                throw unavailable(Reason.PROVIDER_ERROR, "Groq's content is not a JSON object", null);
            }
            return filters;
        } catch (JsonProcessingException e) {
            throw unavailable(Reason.PROVIDER_ERROR, "Groq's answer is not JSON", e);
        }
    }

    private static InterpreterUnavailableException unavailable(Reason reason, String message, Throwable cause) {
        log.warn("Natural-language search unavailable ({}): {}", reason, message);
        return new InterpreterUnavailableException(reason, message, cause);
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
