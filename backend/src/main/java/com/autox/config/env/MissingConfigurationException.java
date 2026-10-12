package com.autox.config.env;

import java.util.List;

/**
 * Thrown when the application is started without the configuration it needs.
 *
 * <p>Carries the findings rather than a formatted string, so
 * {@link MissingConfigurationFailureAnalyzer} can render them as a Spring Boot failure
 * report — the banner with a Description and an Action — instead of a stack trace. That
 * report is the entire point of this type: the failure it replaces was a hundred and fifty
 * lines deep and never said which variable was missing.
 */
public class MissingConfigurationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** One unconfigured setting, and what is wrong with it. */
    public record Finding(RequiredSetting setting, Problem problem, String actualValue) {

        public enum Problem {
            /** Nothing supplied it: no environment variable, no .env entry, no default. */
            MISSING,
            /** Supplied but empty — `AUTOX_JWT_SECRET=` in a copied .env.example. */
            BLANK,
            /** Supplied but the wrong shape, e.g. a datasource URL not starting `jdbc:`. */
            MALFORMED,
        }
    }

    private final transient List<Finding> findings;
    private final transient boolean dotenvFileFound;

    public MissingConfigurationException(List<Finding> findings, boolean dotenvFileFound) {
        super(summary(findings));
        this.findings = List.copyOf(findings);
        this.dotenvFileFound = dotenvFileFound;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    /**
     * Whether a {@code .env} was loaded. Changes the advice completely: without one the fix
     * is {@code cp .env.example .env}, with one the fix is to edit the file that is already
     * there — and telling someone to create a file they have is how a good error message
     * becomes a confusing one.
     */
    public boolean isDotenvFileFound() {
        return dotenvFileFound;
    }

    /**
     * A one-line message for the rare path where no failure analyzer runs — a test
     * asserting on the exception, or an embedded launch that bypasses Boot's reporting.
     */
    private static String summary(List<Finding> findings) {
        return "Missing required configuration: "
                + String.join(", ", findings.stream().map(f -> f.setting().variable()).sorted().toList());
    }
}
