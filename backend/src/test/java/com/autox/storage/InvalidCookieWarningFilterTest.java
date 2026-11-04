package com.autox.storage;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/**
 * The real client, against a stub answering as Cloudflare does: a {@code __cf_bm} cookie with a date
 * the client cannot read, on every response.
 */
class InvalidCookieWarningFilterTest {

    private final LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    private final Logger cookies = context.getLogger(InvalidCookieWarningFilter.LOGGER);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();
    private HttpServer stub;

    @BeforeEach
    void setUp() throws Exception {
        removeFilter();
        lines.start();
        cookies.addAppender(lines);
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "__cf_bm=abc; path=/; expires=not-a-date; HttpOnly");
            exchange.getResponseHeaders().add("ETag", "\"x\"");
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        stub.start();
    }

    @AfterEach
    void tearDown() {
        stub.stop(0);
        cookies.detachAppender(lines);
        removeFilter();
    }

    private void removeFilter() {
        context.getTurboFilterList().removeIf(InvalidCookieWarningFilter.class::isInstance);
    }

    private void callStorage() {
        try (S3Client client = S3Client.builder()
                .region(Region.EU_WEST_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("a", "b")))
                .httpClientBuilder(ApacheHttpClient.builder())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .endpointOverride(URI.create("http://127.0.0.1:" + stub.getAddress().getPort()))
                .build()) {
            client.headObject(request -> request.bucket("b").key("k"));
        }
    }

    @Test
    @DisplayName("without the filter the client does warn, so this test is testing something")
    void theWarningIsReal() {
        callStorage();

        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.startsWith("Invalid cookie header") && line.contains("__cf_bm"));
    }

    @Test
    @DisplayName("with it, a call to storage logs no such line")
    void theWarningIsGone() {
        InvalidCookieWarningFilter.install();

        callStorage();

        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.startsWith("Invalid cookie header"));
    }

    @Test
    @DisplayName("any other warning from that logger still appears, and other loggers saying the same words too")
    void nothingElseIsHidden() {
        InvalidCookieWarningFilter.install();
        Logger other = context.getLogger("com.autox.somewhere");
        other.addAppender(lines);
        try {
            cookies.warn("Cookie rejected [__cf_bm=abc]");
            cookies.error("Something else entirely");
            other.warn("Invalid cookie header, said by someone else");
        } finally {
            other.detachAppender(lines);
        }

        assertThat(lines.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
                "Cookie rejected [__cf_bm=abc]", "Something else entirely", "Invalid cookie header, said by someone else");
        assertThat(lines.list).extracting(ILoggingEvent::getLevel).contains(Level.WARN, Level.ERROR);
        assertThat(cookies.getLevel()).as("the logger's own level is not touched").isNull();
    }

    @Test
    @DisplayName("installing it twice does not add it twice")
    void idempotent() {
        InvalidCookieWarningFilter.install();
        InvalidCookieWarningFilter.install();

        assertThat(context.getTurboFilterList().stream().filter(InvalidCookieWarningFilter.class::isInstance)).hasSize(1);
    }
}
