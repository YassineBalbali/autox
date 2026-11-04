package com.autox.mail;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends through Brevo's transactional API: {@code POST /smtp/email}.
 *
 * <p>No {@code replyTo} is sent, so a reply never reaches another user of the site. The outbox
 * row's id goes as Brevo's {@code IdempotencyKey} header, the same on every attempt, so a send
 * repeated after a lost answer has a chance of not arriving twice.
 *
 * <p>A failure is reported from the status and Brevo's own error code and message, never from the
 * request. It goes to the outbox row and the log, and neither may hold a body or the key.
 */
@Component
@ConditionalOnProperty(name = "autox.mail.provider", havingValue = "brevo")
public class BrevoEmailSender implements EmailSender {

    private final RestClient client;
    private final MailProperties properties;
    private final ObjectMapper json;

    public BrevoEmailSender(MailProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.timeout()).build());
        requests.setReadTimeout(properties.timeout());
        this.client = RestClient.builder()
                .requestFactory(requests)
                .baseUrl(properties.baseUrl())
                .defaultHeader("api-key", properties.apiKey())
                .build();
    }

    @Override
    public void send(EmailMessage message) {
        try {
            client.post()
                    .uri("/smtp/email")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(new SendRequest(
                            new Address(properties.fromName(), properties.fromAddress()),
                            List.of(new Address(null, message.to())),
                            message.subject(),
                            message.textBody(),
                            message.htmlBody(),
                            Map.of("IdempotencyKey", message.messageId())))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException refused) {
            throw new EmailDeliveryException(describe(refused));
        } catch (RestClientException unreachable) {
            throw new EmailDeliveryException("Brevo could not be reached: " + unreachable.getClass().getSimpleName());
        }
    }

    /** "HTTP 401 unauthorized: Key not found", or just the status if the body is not Brevo's. */
    private String describe(RestClientResponseException refused) {
        String status = "HTTP " + refused.getStatusCode().value();
        try {
            JsonNode error = json.readTree(refused.getResponseBodyAsString());
            String code = error.path("code").asText("");
            String detail = error.path("message").asText("");
            return (code + ": " + detail).equals(": ") ? status : status + " " + code + ": " + detail;
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return status;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Address(String name, String email) {
    }

    record SendRequest(Address sender, List<Address> to, String subject, String textContent, String htmlContent,
                       Map<String, String> headers) {
    }
}
