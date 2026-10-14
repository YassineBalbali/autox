package com.autox.throttle;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.AbstractIntegrationTest;
import com.autox.user.UserRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;

/**
 * Who the throttles think the client is, behind a proxy — and that the client cannot
 * choose the answer.
 *
 * <p>On a real port, because the work is done by Tomcat's RemoteIpValve, below the
 * servlet layer MockMvc starts at. The observable is the registration counter's key: each
 * request registers one account, and the row it adds is named after the address the
 * application believed.
 *
 * <p>The test client connects from 127.0.0.1, which is inside the default internal-proxy
 * ranges, so it plays the platform's proxy — except in the last group, which takes that
 * trust away.
 *
 * <p>Each group has its own configuration, and so its own application context and port.
 * The port and the counters are therefore fields of {@link Probe}, which each group
 * extends: injected into the enclosing instance, they would come from the outer context,
 * and every request would reach a server with the default configuration.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "autox.auth.registration-throttle.enabled=true",
        "autox.auth.registration-throttle.max-attempts-per-window=1000"})
class ClientAddressIntegrationTest extends AbstractIntegrationTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Nested
    @DisplayName("default: X-Forwarded-For, read from the right")
    class ForwardedFor extends Probe {

        @Test
        @DisplayName("a forged first value is ignored; the client is the hop the proxy appended")
        void forgedFirstValue() throws Exception {
            // What an appending proxy produces when the client sent its own header.
            register("X-Forwarded-For", "198.51.100.66, 203.0.113.20");

            assertCounted("203.0.113.20");
            assertNotCounted("198.51.100.66");
        }

        @Test
        @DisplayName("internal hops on the right are skipped")
        void internalHopsSkipped() throws Exception {
            register("X-Forwarded-For", "203.0.113.21, 10.1.2.3");

            assertCounted("203.0.113.21");
            assertNotCounted("10.1.2.3");
        }

        @Test
        @DisplayName("True-Client-IP means nothing unless configured")
        void otherHeadersIgnored() throws Exception {
            register("True-Client-IP", "198.51.100.77");

            assertNotCounted("198.51.100.77");
            assertCounted("127.0.0.1");
        }
    }

    @Nested
    @TestPropertySource(properties = "server.tomcat.remoteip.remote-ip-header=True-Client-IP")
    @DisplayName("on Render: True-Client-IP")
    class TrueClientIp extends Probe {

        @Test
        @DisplayName("the header Render sets is the client, whatever X-Forwarded-For says")
        void renderShapedRequest() throws Exception {
            // As Render delivers it: the client's forgery, the client, a Cloudflare edge,
            // Render's own hop — and True-Client-IP, which Render overwrites.
            register(
                    "X-Forwarded-For", "198.51.100.66, 203.0.113.30, 172.71.1.1, 10.0.0.5",
                    "True-Client-IP", "203.0.113.30");

            assertCounted("203.0.113.30");
            assertNotCounted("198.51.100.66");
            assertNotCounted("172.71.1.1");
        }

        @Test
        @DisplayName("an IPv6 client is still keyed on its /64")
        void ipv6() throws Exception {
            register("True-Client-IP", "2001:db8:5:6::42");

            assertCounted("2001:db8:5:6:0:0:0:0/64");
        }

        @Test
        @DisplayName("without it, X-Forwarded-For is not consulted at all")
        void noFallbackToForwardedFor() throws Exception {
            register("X-Forwarded-For", "198.51.100.88");

            assertNotCounted("198.51.100.88");
            assertCounted("127.0.0.1");
        }
    }

    @Nested
    @TestPropertySource(properties = "server.tomcat.remoteip.internal-proxies=10[.]9[.]9[.]9")
    @DisplayName("from a peer that is not a trusted proxy")
    class UntrustedPeer extends Probe {

        @Test
        @DisplayName("the forwarding header is ignored and the socket address is the client")
        void headerIgnored() throws Exception {
            register("X-Forwarded-For", "198.51.100.99");

            assertNotCounted("198.51.100.99");
            assertCounted("127.0.0.1");
        }
    }

    /** One group's server and counters, injected from that group's own context. */
    abstract class Probe {

        @LocalServerPort int port;
        @Autowired ThrottleCounters counters;

        void register(String... headers) throws Exception {
            Map<String, Object> payload = registrationPayload(uniqueEmail("address"), UserRole.PARTICULIER);
            HttpResponse<String> response = HTTP.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/register"))
                            .header("Content-Type", "application/json")
                            .headers(headers)
                            .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        }

        void assertCounted(String key) {
            assertThat(counters.find(ThrottleScope.REGISTRATION_IP, key)).as("counter for %s", key).isPresent();
        }

        void assertNotCounted(String key) {
            assertThat(counters.find(ThrottleScope.REGISTRATION_IP, key)).as("counter for %s", key).isEmpty();
        }
    }
}
