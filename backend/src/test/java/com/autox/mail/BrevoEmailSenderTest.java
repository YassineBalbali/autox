package com.autox.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The real client against a local server that answers as Brevo does. No outside service. */
class BrevoEmailSenderTest {

    private static final String KEY = "a-key-that-must-not-travel";
    private static final EmailMessage MESSAGE = new EmailMessage("recipient@autox.test", "Objet é",
            "Le texte", "<p>Le HTML</p>", "0190a5d0-0000-7000-8000-000000000001");

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> requestKey = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();

    private HttpServer server;
    private int answerStatus = 201;
    private String answerBody = "{\"messageId\":\"<1@smtp-relay.mailin.fr>\"}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            requestKey.set(exchange.getRequestHeaders().getFirst("api-key"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] answer = answerBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answerStatus, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private BrevoEmailSender sender() {
        return new BrevoEmailSender(properties("http://127.0.0.1:" + server.getAddress().getPort() + "/v3"), json);
    }

    private static MailProperties properties(String baseUrl) {
        return new MailProperties("brevo", KEY, "sender@autox.test", "AutoX", baseUrl, Duration.ofSeconds(2),
                null, null, null);
    }

    @Test
    @DisplayName("posts the transactional request Brevo documents, from AutoX, with no Reply-To")
    void sends() throws Exception {
        sender().send(MESSAGE);

        assertThat(requestPath.get()).isEqualTo("POST /v3/smtp/email");
        assertThat(requestKey.get()).isEqualTo(KEY);
        JsonNode body = json.readTree(requestBody.get());
        assertThat(body.at("/sender/name").asText()).isEqualTo("AutoX");
        assertThat(body.at("/sender/email").asText()).isEqualTo("sender@autox.test");
        assertThat(body.at("/to/0/email").asText()).isEqualTo("recipient@autox.test");
        assertThat(body.at("/to/0").has("name")).isFalse();
        assertThat(body.get("subject").asText()).isEqualTo("Objet é");
        assertThat(body.get("textContent").asText()).isEqualTo("Le texte");
        assertThat(body.get("htmlContent").asText()).isEqualTo("<p>Le HTML</p>");
        assertThat(body.has("replyTo")).isFalse();
        assertThat(body.at("/headers/IdempotencyKey").asText()).isEqualTo(MESSAGE.messageId());
    }

    @Test
    @DisplayName("a refusal is a delivery failure that says why, from Brevo's answer and not the request")
    void refused() {
        answerStatus = 401;
        answerBody = "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}";

        assertThatThrownBy(() -> sender().send(MESSAGE))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("HTTP 401 unauthorized: Key not found")
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain(KEY).doesNotContain("Le texte").doesNotContain("recipient@autox.test"));
    }

    @Test
    @DisplayName("an answer that is not Brevo's error shape is reported by its status alone")
    void refusedByAnythingElse() {
        answerStatus = 502;
        answerBody = "<html>Bad gateway</html>";

        assertThatThrownBy(() -> sender().send(MESSAGE))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessage("HTTP 502");
    }

    @Test
    @DisplayName("a provider that cannot be reached is a delivery failure too")
    void unreachable() {
        server.stop(0);

        assertThatThrownBy(() -> sender().send(MESSAGE))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageStartingWith("Brevo could not be reached");
    }

    @Test
    @DisplayName("printing the configuration cannot print the key")
    void keyIsNotPrinted() {
        assertThat(properties("http://localhost").toString()).doesNotContain(KEY);
    }
}
