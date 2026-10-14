package com.autox.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Nothing internal reaches a client, whatever the client sends.
 *
 * <p>Every other test in this suite goes through MockMvc, which starts at the servlet
 * filters. This one listens on a real port, because half of what it checks happens
 * <em>below</em> Spring: a request line the HTTP parser rejects is answered by Tomcat
 * itself, and no MockMvc test can ever see that answer.
 *
 * <p>The assertions are of two kinds. Each response must be the API's own problem
 * document — JSON, with a {@code code} — and none may contain anything that describes the
 * server instead of the request: a class name, a stack frame, SQL, a product name.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NoInternalDetailIntegrationTest extends AbstractIntegrationTest {

    /** What must never appear in a response body or header. */
    private static final List<Pattern> INTERNAL = List.of(
            Pattern.compile("\\bat [a-z]+\\.[\\w.$]+\\("),          // a stack frame
            Pattern.compile("Exception\\b"),
            Pattern.compile("org\\.springframework|com\\.autox|jakarta\\.|com\\.fasterxml"),
            Pattern.compile("\\bjava\\.(lang|util|sql|time)\\b"),
            Pattern.compile("(?i)hibernate|jackson|tomcat|apache|whitelabel|spring boot"),
            Pattern.compile("(?i)\\bselect\\b|\\binsert\\b|relation \"|syntax error|postgres|jdbc"),
            Pattern.compile("(?i)\"trace\"|stacktrace"));

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @LocalServerPort int port;

    // --- below Spring: the HTTP parser's own refusals ---------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "GET /api/v1/listings/[{^}] HTTP/1.1",
            "GET /api/v1/listings?q=<script>|x HTTP/1.1",
            "GET /api/v1/listings/..%2F..%2Fsecret HTTP/1.1",
            "GET / HTTP/9.9",
            "HELLO",
    })
    @DisplayName("a request the parser rejects is answered with the API's problem document, not Tomcat's page")
    void parserRejections(String requestLine) throws Exception {
        String answer = raw(requestLine + "\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        String head = answer.substring(0, Math.max(0, answer.indexOf("\r\n\r\n")));
        String body = answer.substring(Math.min(answer.length(), answer.indexOf("\r\n\r\n") + 4));

        assertThat(head).startsWith("HTTP/1.1 ");
        assertThat(Integer.parseInt(head.substring(9, 12))).isBetween(400, 505);
        assertThat(head).containsIgnoringCase("Content-Type: application/problem+json");
        assertThat(head).doesNotContainIgnoringCase("text/html").doesNotContainIgnoringCase("\nServer:");

        JsonNode problem = objectMapper.readTree(body);
        assertThat(problem.get("code").asText()).isIn("MALFORMED_REQUEST", "INTERNAL_ERROR");
        assertThat(problem.get("status").asInt()).isEqualTo(Integer.parseInt(head.substring(9, 12)));
        assertThat(problem.hasNonNull("timestamp")).isTrue();
        // Nothing of the request is echoed back: it is the thing that could not be parsed.
        assertThat(body).doesNotContain("script").doesNotContain("secret").doesNotContain("HELLO");
        assertClean(answer);
    }

    @Test
    @DisplayName("an oversized header is refused the same way")
    void oversizedHeader() throws Exception {
        String answer = raw("GET / HTTP/1.1\r\nHost: localhost\r\nX-Big: " + "a".repeat(70_000)
                + "\r\nConnection: close\r\n\r\n");

        assertThat(answer).containsIgnoringCase("application/problem+json").contains("MALFORMED_REQUEST");
        assertClean(answer);
    }

    // --- in Spring MVC: the framework's own errors ------------------------------

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "/api/v1/listings/not-a-uuid",
            "/api/v1/listings?priceMin=abc",
            "/api/v1/listings?fuelType=PLUTONIUM",
            "/api/v1/listings?sort=price;DROP%20TABLE%20listing",
            "/api/v1/listings?cursor=eyJhIjoxfQ",
            "/api/v1/listings?q=%27%3B%20DROP%20TABLE%20listing%3B%20--",
            "/api/v1/listings?q=%00",
            "/api/v1/listings?size=100000",
            "/api/v1/reference/makes/xyz/models",
            "/api/v1/nope",
            "/nope",
            "/error",
            "/actuator/env",
            "/api/v1/me",
            "/api/v1/admin/listings",
    })
    @DisplayName("a GET that cannot succeed says so in the contract's shape and in nothing else")
    void hostileGets(String path) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        // Some of these are legitimately fine (a search that matches nothing). The ones
        // that are refused must be refused properly.
        if (response.statusCode() >= 400) {
            assertProblem(response);
        }
        assertClean(response.body() + response.headers().map());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "{\"email\": ",
            "[1,2,3]",
            "{\"email\": {\"a\": 1}, \"password\": []}",
            "{\"email\":\"a@b.fr\",\"password\":\"aaaaaaaaaaaaaaaa\",\"role\":\"EMPEROR\",\"displayName\":\"x\",\"countryCode\":\"FR\"}",
            "{\"email\":\"a@b.fr\",\"password\":\"aaaaaaaaaaaaaaaa\",\"role\":7,\"displayName\":\"x\",\"countryCode\":\"FR\"}",
            "{\"email\":\"a\\u0000@b.fr\",\"password\":\"aaaaaaaaaaaaaaaa\",\"role\":\"PARTICULIER\",\"displayName\":\"x\",\"countryCode\":\"FR\"}",
            "{\"email\":\"leak@b.fr\",\"password\":\"aaaaaaaaaaaaaaaa\",\"role\":\"PARTICULIER\",\"displayName\":\"x\",\"countryCode\":\"ZZ\"}",
    })
    @DisplayName("a body that cannot be accepted is refused without quoting the parser or the model")
    void hostileBodies(String body) throws Exception {
        HttpResponse<String> response = HTTP.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isBetween(400, 499);
        assertProblem(response);
        assertClean(response.body());
    }

    @Test
    @DisplayName("the framework's own refusals carry a code, like every other error")
    void frameworkErrorsFollowTheContract() throws Exception {
        URI login = URI.create("http://localhost:" + port + "/api/v1/auth/login");

        HttpResponse<String> wrongType = HTTP.send(HttpRequest.newBuilder(login)
                .header("Content-Type", "text/xml").POST(HttpRequest.BodyPublishers.ofString("<a/>")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(wrongType.statusCode()).isEqualTo(415);
        assertThat(objectMapper.readTree(wrongType.body()).get("code").asText()).isEqualTo("UNSUPPORTED_MEDIA_TYPE");

        HttpResponse<String> wrongAccept = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/listings"))
                .header("Accept", "text/csv").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(wrongAccept.statusCode()).isEqualTo(406);
        assertProblem(wrongAccept);
        // It used to list the server's message converters here.
        assertThat(wrongAccept.body()).doesNotContain("yaml").doesNotContain("[");

        HttpResponse<String> badId = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/listings/not-a-uuid")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(badId.statusCode()).isEqualTo(400);
        JsonNode problem = objectMapper.readTree(badId.body());
        assertThat(problem.get("code").asText()).isEqualTo("VALIDATION_FAILED");
        // Which parameter — and neither its value nor the type it failed to become.
        assertThat(problem.get("errors").get(0).get("field").asText()).isEqualTo("id");
        // (`instance` is the request path, by the contract, and so does contain it.)
        assertThat(problem.get("detail").asText() + problem.get("errors")).doesNotContain("not-a-uuid").doesNotContain("UUID");
    }

    @Test
    @DisplayName("a search parameter of the wrong type is named, and its Java type is not")
    void bindingFailuresDoNotNameClasses() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/v1/listings?fuelType=PLUTONIUM&priceMin=abc")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(400);
        JsonNode errors = objectMapper.readTree(response.body()).get("errors");
        assertThat(errors).hasSize(2);
        assertThat(errors.get(0).get("field").asText()).isEqualTo("fuelType");
        assertThat(errors.get(1).get("field").asText()).isEqualTo("priceMin");
        assertThat(response.body()).doesNotContain("FuelType").doesNotContain("Long")
                .doesNotContain("PLUTONIUM").doesNotContain("abc");
        assertClean(response.body());
    }

    @Test
    @DisplayName("no response says what the server is")
    void noServerBanner() throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/listings")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("Server")).isEmpty();
        assertThat(response.headers().firstValue("X-Powered-By")).isEmpty();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }

    @Test
    @DisplayName("API responses forbid being framed or loading anything; the Swagger UI keeps its scripts")
    void securityHeaders() throws Exception {
        HttpResponse<String> api = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/reference/countries")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(api.headers().firstValue("Content-Security-Policy"))
                .hasValue("default-src 'none'; frame-ancestors 'none'");
        assertThat(api.headers().firstValue("Referrer-Policy")).hasValue("no-referrer");
        assertThat(api.headers().firstValue("Permissions-Policy")).isPresent();

        // An error is a response like any other.
        HttpResponse<String> refused = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/me")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(refused.headers().firstValue("Content-Security-Policy")).isPresent();

        // The documentation is a page: the same policy would leave it blank.
        HttpResponse<String> docs = HTTP.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/swagger-ui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(docs.statusCode()).isEqualTo(200);
        assertThat(docs.headers().firstValue("Content-Security-Policy")).isEmpty();
        assertThat(docs.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }

    // --- helpers ----------------------------------------------------------------

    private void assertProblem(HttpResponse<String> response) throws Exception {
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .as("content type of %s", response.uri()).contains("json");
        JsonNode problem = objectMapper.readTree(response.body());
        assertThat(problem.hasNonNull("code")).as("code in %s", response.body()).isTrue();
        assertThat(problem.get("status").asInt()).isEqualTo(response.statusCode());
    }

    private static void assertClean(String text) {
        for (Pattern pattern : INTERNAL) {
            assertThat(pattern.matcher(text).find())
                    .as("'%s' found in: %s", pattern.pattern(), text.length() > 600 ? text.substring(0, 600) : text)
                    .isFalse();
        }
    }

    /** For what an HTTP client refuses to send. */
    private String raw(String request) throws Exception {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
