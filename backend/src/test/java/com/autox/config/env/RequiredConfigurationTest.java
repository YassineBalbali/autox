package com.autox.config.env;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.config.env.MissingConfigurationException.Finding;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * The fail-fast configuration check, and the message it produces.
 *
 * <p>Tested against a synthetic environment rather than by starting the application, which
 * is both faster and the only way to assert on the <em>message</em> — the message being the
 * entire point. What it replaces was
 * {@code java.lang.IllegalArgumentException: 'url' must start with "jdbc"} at the end of a
 * hundred and fifty lines, and later
 * {@code FATAL: password authentication failed for user "autox"}: two errors that blame
 * Hikari and PostgreSQL for a variable nobody set.
 */
class RequiredConfigurationTest {

    /** An environment with everything required present and valid. */
    private static Map<String, Object> validConfiguration() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.datasource.url", "jdbc:postgresql://localhost:5433/autox");
        properties.put("spring.datasource.password", "a-password");
        properties.put("autox.auth.jwt-secret", "a-signing-key-of-at-least-32-bytes-length");
        properties.put("autox.storage.access-key", "an-access-key");
        properties.put("autox.storage.secret-key", "a-secret-key");
        return properties;
    }

    private static StandardEnvironment environmentWith(Map<String, Object> properties) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", properties));
        return environment;
    }

    @Test
    @DisplayName("a fully configured environment produces no findings")
    void fullyConfiguredPasses() {
        assertThat(RequiredConfigurationEnvironmentPostProcessor.inspect(
                environmentWith(validConfiguration())))
                .isEmpty();
    }

    @Test
    @DisplayName("an unresolved placeholder counts as not set")
    void unresolvedPlaceholderIsMissing() {
        // The actual root cause. application.yml wires these to ${AUTOX_*} with no default,
        // and when the variable is absent Spring returns the placeholder TEXT — not null,
        // not an exception. That literal then travels on as if it were a value, which is
        // how "${AUTOX_DB_PASSWORD}" ended up being offered to PostgreSQL as a password.
        Map<String, Object> properties = validConfiguration();
        properties.put("spring.datasource.password", "${AUTOX_DB_PASSWORD}");
        properties.put("autox.auth.jwt-secret", "${AUTOX_JWT_SECRET}");

        List<Finding> findings =
                RequiredConfigurationEnvironmentPostProcessor.inspect(environmentWith(properties));

        assertThat(findings).extracting(finding -> finding.setting().variable())
                .containsExactly("AUTOX_DB_PASSWORD", "AUTOX_JWT_SECRET");
        assertThat(findings).allMatch(finding -> finding.problem() == Finding.Problem.MISSING);
    }

    @Test
    @DisplayName("an awkward but placeholder-free credential is left alone")
    void unusualCharactersAreFine() {
        // The comparison is against the exact placeholder for that variable, not a search
        // for a dollar sign: reporting a correctly-set credential as missing would be a
        // worse bug than the one being fixed.
        Map<String, Object> properties = validConfiguration();
        properties.put("spring.datasource.password", "p$w0rd#with@odd~chars");

        assertThat(RequiredConfigurationEnvironmentPostProcessor.inspect(environmentWith(properties)))
                .isEmpty();
    }

    @Test
    @DisplayName("a credential containing ${ cannot be used at all — and that is Spring, not this check")
    void dollarBraceInACredentialIsAnUnusableValue() {
        // Worth a test because it is a real constraint on what a developer may put in .env,
        // and it is invisible until it bites. Spring resolves nested placeholders inside
        // every property value, so a password of literally p${ass}word makes
        // environment.getProperty() throw — it is trying to expand ${ass}. Nothing here can
        // rescue that: the value is unusable before this check ever sees it.
        //
        // So it is reported as not set, which is the least misleading of the available
        // answers, and .env.example warns against the character sequence.
        Map<String, Object> properties = validConfiguration();
        properties.put("spring.datasource.password", "p${ass}word");

        assertThat(RequiredConfigurationEnvironmentPostProcessor.inspect(environmentWith(properties)))
                .extracting(finding -> finding.setting().variable())
                .containsExactly("AUTOX_DB_PASSWORD");
    }

    @Test
    @DisplayName("an empty value is reported as set-but-empty, not as missing")
    void blankIsDistinguishedFromMissing() {
        // .env.example ships `AUTOX_JWT_SECRET=` blank on purpose, so this is the state of
        // every freshly copied file. "is not set" would be a lie about a line the developer
        // is looking straight at.
        Map<String, Object> properties = validConfiguration();
        properties.put("autox.auth.jwt-secret", "");
        properties.put("autox.storage.secret-key", "   ");

        List<Finding> findings =
                RequiredConfigurationEnvironmentPostProcessor.inspect(environmentWith(properties));

        assertThat(findings).extracting(Finding::problem)
                .containsOnly(Finding.Problem.BLANK);
        assertThat(findings).extracting(finding -> finding.setting().variable())
                .containsExactlyInAnyOrder("AUTOX_JWT_SECRET", "AUTOX_S3_SECRET_KEY");
    }

    @Test
    @DisplayName("a datasource URL that is not a JDBC URL is caught here, not by Hikari")
    void malformedJdbcUrlIsCaught() {
        // The original failure, pre-empted: Hikari's assertion is the thing this check
        // exists to replace, so the malformed case matters as much as the missing one.
        Map<String, Object> properties = validConfiguration();
        properties.put("spring.datasource.url", "postgresql://localhost:5433/autox");

        List<Finding> findings =
                RequiredConfigurationEnvironmentPostProcessor.inspect(environmentWith(properties));

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.setting().variable()).isEqualTo("AUTOX_DB_URL");
            assertThat(finding.problem()).isEqualTo(Finding.Problem.MALFORMED);
            assertThat(finding.actualValue()).isEqualTo("postgresql://localhost:5433/autox");
        });
    }

    @Test
    @DisplayName("an environment with nothing in it reports every required setting")
    void emptyEnvironmentReportsEverything() {
        // A bare StandardEnvironment has no application.yml behind it, so not even the
        // defaulted coordinates resolve. That is the right assertion for this method:
        // nothing is assumed to be present.
        List<Finding> findings =
                RequiredConfigurationEnvironmentPostProcessor.inspect(new StandardEnvironment());

        assertThat(findings).extracting(finding -> finding.setting().variable())
                .containsExactlyInAnyOrder(
                        "AUTOX_DB_URL",
                        "AUTOX_DB_PASSWORD",
                        "AUTOX_JWT_SECRET",
                        "AUTOX_S3_ACCESS_KEY",
                        "AUTOX_S3_SECRET_KEY");
    }

    @Test
    @DisplayName("application.yml defaults the coordinates and defaults no credential")
    void defaultsCoverCoordinatesButNeverCredentials() {
        // The standing rule: credentials come from the environment with no fallback. A
        // default password is a password in version control, and one that works locally is
        // one that reaches a server eventually. Asserted against the file itself, because
        // that is where the rule is either kept or quietly broken.
        String configuration = readClasspath("application.yml");

        // Non-secret coordinates: defaulted, so a stock local stack starts.
        assertThat(configuration)
                .contains("${AUTOX_DB_URL:jdbc:postgresql://localhost:5433/autox}")
                .contains("${AUTOX_DB_USERNAME:autox}");

        // Credentials: referenced with no default, i.e. "${VAR}" and never "${VAR:".
        for (String credential : List.of(
                "AUTOX_DB_PASSWORD", "AUTOX_JWT_SECRET", "AUTOX_S3_ACCESS_KEY", "AUTOX_S3_SECRET_KEY")) {
            assertThat(configuration)
                    .as(credential + " must be referenced without a default")
                    .contains("${" + credential + "}")
                    .doesNotContain("${" + credential + ":");
        }
    }

    private static String readClasspath(String resource) {
        try (var stream = RequiredConfigurationTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(stream).as(resource + " on the classpath").isNotNull();
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("Could not read " + resource, e);
        }
    }

    // --- the message ------------------------------------------------------

    @Test
    @DisplayName("the failure names every variable, what it needs, and the property it sets")
    void messageNamesTheVariables() {
        List<Finding> findings =
                RequiredConfigurationEnvironmentPostProcessor.inspect(new StandardEnvironment());
        FailureAnalysis analysis = analyze(findings, false);

        assertThat(analysis.getDescription())
                .contains("AUTOX_DB_PASSWORD")
                .contains("AUTOX_JWT_SECRET")
                .contains("is not set")
                // What a valid value looks like, so the next step does not need a search.
                .contains("openssl rand -base64 48")
                // And which Spring property it feeds, for anyone reading application.yml.
                .contains("spring.datasource.password");

        // The thing that must never come back.
        assertThat(analysis.getDescription()).doesNotContain("must start with");
    }

    @Test
    @DisplayName("with no .env, the action is to create one from the example")
    void actionWithoutDotenvFile() {
        FailureAnalysis analysis = analyze(
                RequiredConfigurationEnvironmentPostProcessor.inspect(new StandardEnvironment()),
                false);

        assertThat(analysis.getAction())
                // A deployed container has no .env either, and is told so first.
                .contains("On a server or in a container")
                .contains("docs/deployment.md")
                .contains("no .env file was found")
                .contains("cp .env.example .env")
                .contains("docker compose up -d")
                // Says where it looked, so "but I made one" has an answer.
                .contains("backend");
    }

    @Test
    @DisplayName("with a .env present, the action is to edit it — not to create it again")
    void actionWithDotenvFile() {
        FailureAnalysis analysis = analyze(
                RequiredConfigurationEnvironmentPostProcessor.inspect(new StandardEnvironment()),
                true);

        // Telling someone to create a file they are looking at is how a good error message
        // becomes a confusing one.
        assertThat(analysis.getAction())
                .contains("Edit the .env")
                .doesNotContain("cp .env.example");
        // And the one thing that explains a value in the file appearing to be ignored.
        assertThat(analysis.getAction()).contains("overrides .env");
    }

    @Test
    @DisplayName("no credential value is echoed into the failure message")
    void messageNeverEchoesACredential() {
        // MALFORMED is the one problem that quotes the offending value, and the datasource
        // URL is the only setting with a shape check — so a password can never be printed.
        // Worth asserting: an error report is a thing people paste into chat.
        Map<String, Object> properties = validConfiguration();
        properties.put("spring.datasource.password", "");
        properties.put("autox.auth.jwt-secret", "");
        properties.put("autox.storage.access-key", "super-secret-value");
        properties.put("autox.storage.secret-key", "another-secret-value");

        FailureAnalysis analysis = analyze(
                RequiredConfigurationEnvironmentPostProcessor.inspect(environmentWith(properties)),
                true);

        assertThat(analysis.getDescription())
                .doesNotContain("super-secret-value")
                .doesNotContain("another-secret-value");
    }

    private static FailureAnalysis analyze(List<Finding> findings, boolean dotenvFound) {
        MissingConfigurationException exception =
                new MissingConfigurationException(findings, dotenvFound);
        FailureAnalysis analysis =
                new MissingConfigurationFailureAnalyzer().analyze(exception, exception);
        assertThat(analysis).isNotNull();
        return analysis;
    }
}
