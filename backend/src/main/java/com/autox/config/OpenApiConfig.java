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
}
