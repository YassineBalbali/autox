package com.autox.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.autox.listing.ListingProperties;
import com.autox.listing.image.ImageProperties;
import com.autox.support.AbstractIntegrationTest;
import com.autox.throttle.PublicRateLimitProperties;
import com.autox.user.UserRole;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;

/**
 * That the suite runs against the production configuration, with the test profile layered
 * over it rather than replacing it.
 *
 * <p>This test exists because for most of the project's life it did not. The test file was
 * named {@code application.yml}, which put it earlier on the classpath than the production
 * one and made Spring load it <em>instead of</em> — not on top of — the real configuration.
 * Everything the production file set and the test file did not was therefore running on a
 * framework default, and nothing said so. It was found by accident, when health-probe
 * configuration had no effect in tests.
 *
 * <p>The failure mode it guards against is the quiet one: not a broken test, but a passing
 * test that is asserting something other than what the application does. So the assertions
 * below come in two halves — production settings that must reach the tests, and test
 * deviations that must still win. Both directions matter; getting only the first would mean
 * the suite had stopped using its own fixtures.
 */
class ConfigurationLayeringIntegrationTest extends AbstractIntegrationTest {

    @Autowired Environment environment;
    @Autowired ListingProperties listingProperties;
    @Autowired ImageProperties imageProperties;
    @Autowired PublicRateLimitProperties rateLimitProperties;
    @Autowired AuthProperties authProperties;
    @Autowired com.autox.auth.throttle.LoginThrottleProperties throttleProperties;

    @Test
    @DisplayName("no application.yml shadows the production one on the test classpath")
    void nothingShadowsTheProductionFile() {
        java.net.URL located = getClass().getClassLoader().getResource("application.yml");

        assertThat(located).as("the production application.yml must be on the classpath").isNotNull();
        // The trap this guards is specific and was hit while making the change: renaming the
        // file in src/ does not remove the copy maven already put in target/test-classes, so
        // an incremental `mvn test` keeps loading the old one and every assertion below still
        // passes for the wrong reason. `mvn clean test` fixes it; this says so out loud
        // instead of leaving the next person to find it the slow way.
        assertThat(located.getPath())
                .as("a stale or new src/test/resources/application.yml is shadowing production "
                        + "configuration — run `mvn clean test`, and keep the test profile in "
                        + "application-test.yml")
                .doesNotContain("test-classes");
    }

    // --- the production file is loaded -----------------------------------

    @Test
    @DisplayName("Hibernate's open-in-view is off, as in production")
    void openInViewIsDisabled() {
        // The one with teeth. With open-in-view enabled the persistence session stays open
        // through response rendering, so a DTO that lazily loads an association during
        // serialisation works. Production has it off, where the same code throws
        // LazyInitializationException. For most of this project the tests ran with it ON:
        // the suite could not have caught that class of bug, and the startup warning saying
        // so was in every test run's output.
        assertThat(environment.getProperty("spring.jpa.open-in-view", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("null fields are omitted from responses, as in production")
    void jacksonOmitsNulls() throws Exception {
        assertThat(objectMapper.getSerializationConfig().getDefaultPropertyInclusion().getValueInclusion())
                .isEqualTo(JsonInclude.Include.NON_NULL);

        // And on a real response. Registering sends no device label, so the field is null —
        // production leaves it out, and until the rename the tests were asserting against
        // bodies that contained `"deviceLabel": null`. No assertion happened to depend on
        // it, which is luck rather than design.
        var session = register(uniqueEmail("config-nulls"), UserRole.PARTICULIER);
        mockMvc.perform(get("/api/v1/me/sessions")
                        .header(HttpHeaders.AUTHORIZATION, bearer(session)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].deviceLabel").doesNotExist())
                .andExpect(jsonPath("$[0].lastUsedAt").doesNotExist());
    }

    @Test
    @DisplayName("the proxy header strategy is in force, as in production")
    void forwardedHeadersAreHonoured() {
        // What makes X-Forwarded-For the client address. The throttles key on it, and the
        // README's deployment warning is about it, yet no test could reach the behaviour
        // while this setting was absent. PublicRateLimitIntegrationTest now exercises it.
        assertThat(environment.getProperty("server.forward-headers-strategy")).isEqualTo("framework");
    }

    @Test
    @DisplayName("error responses never leak a message or a stack trace, as in production")
    void errorDetailsAreSuppressed() {
        assertThat(environment.getProperty("server.error.include-message")).isEqualTo("never");
        assertThat(environment.getProperty("server.error.include-stacktrace")).isEqualTo("never");
        assertThat(environment.getProperty("spring.mvc.problemdetails.enabled", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Hibernate writes timestamps in UTC and batches, as in production")
    void hibernateTuningIsApplied() {
        // A time zone mismatch between test and production is the kind of thing that makes
        // an off-by-one-hour expiry bug reproducible only in one of them.
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone")).isEqualTo("UTC");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.batch_size")).isEqualTo("50");
        assertThat(environment.getProperty("spring.jpa.properties.hibernate.order_inserts", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("Flyway validates on migrate and does not baseline, as in production")
    void flywayPolicyIsApplied() {
        // validate-on-migrate is what turns an edited migration into a failed startup. A
        // suite running without it would not reproduce that.
        assertThat(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("production values the test profile does not mention are inherited")
    void unmentionedProductionValuesReachTheTests() {
        // The test file used to repeat all of these, so they agreed by transcription. Now
        // they agree because there is one copy. The listing expiry is the clearest case: a
        // 60-day window is policy, and a test asserting it should be asserting the policy.
        assertThat(listingProperties.expiryDays()).isEqualTo(60);
        assertThat(authProperties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(authProperties.refreshTokenTtl()).isEqualTo(Duration.ofDays(90));
        assertThat(authProperties.rotateRefreshTokens()).isTrue();
        assertThat(imageProperties.minDimension()).isEqualTo(400);
        assertThat(imageProperties.allowedContentTypes()).containsExactly("image/jpeg", "image/png");
    }

    @Test
    @DisplayName("the actuator exposure allow-list comes from the production file")
    void managementExposureIsInherited() {
        // This is the setting whose silent absence revealed the whole problem: the health
        // probe groups were configured and had no effect, because the block was never read.
        assertThat(environment.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
        assertThat(environment.getProperty("management.endpoint.health.show-details")).isEqualTo("never");
    }

    // --- the test profile still wins -------------------------------------

    @Test
    @DisplayName("the test profile's deliberate deviations override production")
    void testDeviationsStillWin() {
        assertThat(listingProperties.particulierActiveLimit())
                .as("2, not production's 5, so the quota tests reach the cap quickly")
                .isEqualTo(2);
        assertThat(imageProperties.maxPerListing())
                .as("3, not production's 20")
                .isEqualTo(3);
        assertThat(rateLimitProperties.enabled())
                .as("off suite-wide; PublicRateLimitIntegrationTest turns it on for itself")
                .isFalse();
        assertThat(authProperties.issuer()).isEqualTo("https://api.autox.test");
    }

    @Test
    @DisplayName("the login throttle inherits production thresholds and overrides only the delays")
    void partialOverridesMergePerKey() {
        // Proof that layering happens key by key rather than file by file: the test profile
        // names four of this record's eight properties and production supplies the rest, so
        // the bound object is a mixture of the two.
        assertThat(throttleProperties.accountThreshold()).as("production's").isEqualTo(4);
        assertThat(throttleProperties.ipThreshold()).as("production's").isEqualTo(20);
        assertThat(throttleProperties.resetAfter()).as("production's").isEqualTo(Duration.ofHours(24));
        assertThat(throttleProperties.enabled()).as("production's").isTrue();

        assertThat(throttleProperties.accountInitialDelay()).as("the test profile's").isEqualTo(Duration.ofSeconds(1));
        assertThat(throttleProperties.ipMaxDelay()).as("the test profile's").isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("the test signing key is used, and production's has no value to fall back on")
    void secretsComeFromTheTestProfile() {
        // Production deliberately has no default for the signing key: ${AUTOX_JWT_SECRET}
        // with nothing behind it. The test profile supplies one, which is also why the
        // context starts at all without a developer's environment being set up.
        assertThat(authProperties.jwtSecret()).startsWith("test-only-signing-key");
    }
}
