package com.autox.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The API description, served by the application at {@code /v3/api-docs} (JSON),
 * {@code /v3/api-docs.yaml} and {@code /swagger-ui.html}.
 *
 * <p>Generated from the controllers, so it cannot drift from the code: paths, parameters,
 * response shapes and validation constraints all come from the annotations that enforce them.
 * Only the document-level metadata and the security scheme are written by hand.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI autoxOpenApi(@Value("${AUTOX_JWT_ISSUER:https://api.autox.local}") String issuer) {
        return new OpenAPI()
                // One relative server, pinned. Left alone, springdoc derives this from the
                // host and port of whoever fetched the document, so the same application
                // describes itself differently to a developer on :8081, to a test through
                // MockMvc, and to a browser behind a proxy — and the committed snapshot the
                // frontend generates from would never match any of them.
                //
                // "/" is also the honest answer: the API is deployed same-origin with the
                // web app behind one proxy, and a client that needs something else sets its
                // own base path.
                .servers(java.util.List.of(new io.swagger.v3.oas.models.servers.Server()
                        .url("/")
                        .description("Same origin as the caller")))
                .info(new Info()
                        .title("AutoX API")
                        .version("0.1.0")
                        .description("""
                                Vehicle marketplace API. Headless and API-first: this document is \
                                the whole contract, and the Angular web app and a future native \
                                mobile app consume exactly these endpoints.

                                **Conventions that apply everywhere:**

                                * **Money** is an integer in the currency's minor unit, with an \
                                ISO 4217 code beside it. `priceAmount: 1850000, \
                                priceCurrencyCode: "EUR"` is €18,500.00. The scale comes from \
                                `GET /api/v1/reference/countries`.
                                * **Distance** is always kilometres on the wire, whatever a \
                                market displays.
                                * **Errors** are RFC 9457 `application/problem+json` with a \
                                stable `code`. Branch on `code`, never on `detail` — `detail` \
                                is English developer text and is not translated. Validation \
                                failures add a flat `errors` array of `{field, message}`.
                                * **Enums** serialise as their constant name. The full set a \
                                client may receive is at `GET /api/v1/reference/enums`; display \
                                labels are the client's own responsibility.
                                * **List endpoints are cursor-paginated.** Pass the previous \
                                response's `nextCursor` back as `cursor`. There are no page \
                                numbers and no total count.
                                * **Images are never uploaded through this API.** Request a \
                                slot, PUT the bytes straight to object storage with the \
                                pre-signed URL, then confirm.
                                """)
                        .contact(new Contact().name("AutoX")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("""
                                        Access token from `POST /api/v1/auth/login`, sent as \
                                        `Authorization: Bearer <token>`. Short-lived (15 \
                                        minutes); exchange the refresh token at \
                                        `POST /api/v1/auth/refresh` when it expires. Issuer: \
                                        """ + issuer)));
    }

    /**
     * Declares the error body on every operation.
     *
     * <p>Errors are built at runtime as {@code ProblemDetail} with members attached
     * dynamically, which no schema generator can see — so before this, the document
     * described 33 operations and none of their failures, and a generated client had no
     * error type and no {@code ErrorCode}. The one contract every client depends on most
     * was the one thing it had to hand-write.
     *
     * <p>A customizer rather than an annotation per operation: the error shape is uniform
     * across the whole API by design, and 33 copies of the same {@code @ApiResponse} would
     * be 33 places for it to fall out of step.
     *
     * <p>The response code is {@code default}, meaning "any status not listed above". That
     * is accurate — the same body comes back for 400, 401, 403, 404, 409, 413, 415, 429 and
     * 500 — and it keeps the document honest instead of enumerating per-operation status
     * lists that would be guesses.
     */
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer errorResponseCustomizer() {
        return openApi -> {
            io.swagger.v3.oas.models.media.Schema<?> errorSchema =
                    new io.swagger.v3.oas.models.media.Schema<>().$ref("#/components/schemas/ApiError");

            io.swagger.v3.oas.models.responses.ApiResponse errorResponse =
                    new io.swagger.v3.oas.models.responses.ApiResponse()
                            .description("Error. RFC 9457 problem+json; branch on `code`.")
                            .content(new io.swagger.v3.oas.models.media.Content()
                                    .addMediaType("application/problem+json",
                                            new io.swagger.v3.oas.models.media.MediaType().schema(errorSchema)));

            openApi.getPaths().values().forEach(path ->
                    path.readOperations().forEach(operation -> {
                        if (operation.getResponses() == null) {
                            operation.setResponses(new io.swagger.v3.oas.models.responses.ApiResponses());
                        }
                        operation.getResponses().addApiResponse("default", errorResponse);
                    }));
        };
    }

    /**
     * Pulls {@code ApiError} and its nested {@code FieldError} into the components section.
     *
     * <p>The customizer above only writes a {@code $ref}; nothing in any controller
     * signature mentions the type, so springdoc would never resolve it and the reference
     * would dangle — which a client generator reports as a broken document rather than
     * silently ignoring. Declaring it here is what makes the reference resolvable.
     */
    @Bean
    org.springdoc.core.customizers.OpenApiCustomizer errorSchemaCustomizer() {
        return openApi -> {
            var resolved = io.swagger.v3.core.converter.ModelConverters.getInstance()
                    .readAll(com.autox.api.error.ApiErrorResponse.class);
            resolved.forEach((name, schema) -> openApi.getComponents().addSchemas(name, schema));
        };
    }
}
