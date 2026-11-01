package com.autox.config.env;

import com.autox.config.env.MissingConfigurationException.Finding;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

/**
 * Refuses to start, with a message naming the variable, when configuration is missing.
 *
 * <p>Without this the first symptom of an unconfigured datasource is
 * {@code java.lang.IllegalArgumentException: 'url' must start with "jdbc"} at the bottom of
 * a hundred and fifty lines of bean-creation failures — a message that names neither
 * {@code AUTOX_DB_URL} nor {@code .env}, and that sends a developer reading Hikari's
 * source instead of their own configuration.
 *
 * <p>Runs last among the environment post-processors, so {@code .env} and every
 * {@code application*.yml} have been read and what it inspects is the configuration the
 * application would actually have used.
 */
public class RequiredConfigurationEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    /**
     * Skipped under this profile. The integration tests configure themselves — a literal
     * datasource URL in {@code application-test.yml}, storage credentials from the
     * Testcontainers MinIO through {@code @DynamicPropertySource}, which is registered
     * after environment post-processing and so is not visible here. Checking anyway would
     * fail all 231 of them for configuration they deliberately supply by other means.
     *
     * <p>The check is unit-tested directly against a synthetic environment instead, which
     * is the better test regardless: it can assert the message, which starting an
     * application cannot.
     */
    static final String TEST_PROFILE = "test";

    /** After every other environment post-processor, including Boot's config data loader. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        for (String profile : environment.getActiveProfiles()) {
            if (TEST_PROFILE.equalsIgnoreCase(profile)) {
                return;
            }
        }

        List<Finding> findings = inspect(environment);
        if (!findings.isEmpty()) {
            throw new MissingConfigurationException(
                    findings, environment.getPropertySources()
                            .contains(DotenvEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));
        }
    }

    /**
     * Checks every required setting. Package-private and taking a plain {@link Environment}
     * so it can be tested without starting anything.
     */
    static List<Finding> inspect(Environment environment) {
        List<Finding> findings = new ArrayList<>();

        for (RequiredSetting setting : RequiredSetting.all()) {
            String value = resolve(environment, setting);

            if (value == null) {
                findings.add(new Finding(setting, Finding.Problem.MISSING, null));
            } else if (value.isBlank()) {
                findings.add(new Finding(setting, Finding.Problem.BLANK, value));
            } else if (setting.mustStartWith() != null && !value.startsWith(setting.mustStartWith())) {
                findings.add(new Finding(setting, Finding.Problem.MALFORMED, value));
            } else if (setting.variable().equals("AUTOX_DB_URL")) {
                DatabaseTls.problem(value).ifPresent(problem ->
                        findings.add(new Finding(setting, Finding.Problem.UNVERIFIED_TLS, problem)));
            }
        }

        inspectAi(environment, findings);
        return findings;
    }

    /**
     * Natural-language search is optional, so its key is required only once a provider is
     * chosen. A provider the application does not know is reported rather than left to fail as
     * a missing bean: "groq " with a stray space, or "Groq", would otherwise be an unreadable
     * startup failure. Both are normalised the way {@code AiProperties} reads them.
     */
    private static void inspectAi(Environment environment, List<Finding> findings) {
        String provider = resolve(environment, RequiredSetting.AI_PROVIDER);
        String normalised = provider == null || provider.isBlank()
                ? "none" : provider.strip().toLowerCase(java.util.Locale.ROOT);
        if (!normalised.equals("none") && !normalised.equals("groq")) {
            findings.add(new Finding(RequiredSetting.AI_PROVIDER, Finding.Problem.MALFORMED, provider));
            return;
        }
        if (normalised.equals("groq")) {
            String key = resolve(environment, RequiredSetting.AI_API_KEY);
            if (key == null) {
                findings.add(new Finding(RequiredSetting.AI_API_KEY, Finding.Problem.MISSING, null));
            } else if (key.isBlank()) {
                findings.add(new Finding(RequiredSetting.AI_API_KEY, Finding.Problem.BLANK, key));
            }
        }
    }

    /**
     * The property's value, or {@code null} if nothing supplies it.
     *
     * <p>The placeholder comparison is the part that matters, and it is not defensive
     * programming — it is the actual root cause of the failure this class replaces.
     * {@code application.yml} wires these settings to {@code ${AUTOX_*}} with no default,
     * and when the variable is absent <b>Spring hands back the placeholder text itself</b>:
     * neither {@code null} nor an exception, but the literal string
     * {@code "${AUTOX_DB_PASSWORD}"}. That literal then travels onward as though it were a
     * value, which is why the original symptoms were
     * {@code 'url' must start with "jdbc"} and
     * {@code password authentication failed for user "autox"} — two messages that blame
     * Hikari and PostgreSQL for a variable nobody set.
     *
     * <p>Compared against the exact placeholder rather than by searching for
     * <code>${…}</code> anywhere in the value: a password is allowed to contain
     * <code>${</code>, and reporting a correctly-set credential as missing would be a worse
     * bug than the one being fixed. The cost is that rewiring a setting to a nested or
     * defaulted placeholder would slip past this check and fall back to the old unhelpful
     * error — which is a visible degradation, not a silent wrong answer.
     */
    private static String resolve(Environment environment, RequiredSetting setting) {
        String value;
        try {
            value = environment.getProperty(setting.property());
        } catch (IllegalArgumentException unresolvablePlaceholder) {
            // Not the observed behaviour today, but it is the documented one for strict
            // resolution, and it means the same thing.
            return null;
        }

        if (value == null || value.equals("${" + setting.variable() + "}")) {
            return null;
        }
        return value;
    }
}
