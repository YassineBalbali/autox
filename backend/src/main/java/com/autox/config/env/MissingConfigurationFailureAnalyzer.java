package com.autox.config.env;

import com.autox.config.env.MissingConfigurationException.Finding;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns {@link MissingConfigurationException} into Spring Boot's failure report — the
 * {@code APPLICATION FAILED TO START} banner with a Description and an Action — instead of
 * a stack trace.
 *
 * <p>That formatting is the deliverable, not decoration. The failure being replaced ended
 * in {@code 'url' must start with "jdbc"} after a hundred and fifty lines, and said nothing
 * about which variable, which file, or what to do next. This says all three in the first
 * screenful.
 */
public class MissingConfigurationFailureAnalyzer
        extends AbstractFailureAnalyzer<MissingConfigurationException> {

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, MissingConfigurationException cause) {
        return new FailureAnalysis(description(cause), action(cause), cause);
    }

    private String description(MissingConfigurationException cause) {
        StringBuilder description = new StringBuilder("The application is not configured.\n");

        int width = cause.getFindings().stream()
                .mapToInt(finding -> finding.setting().variable().length())
                .max()
                .orElse(20);

        for (Finding finding : cause.getFindings()) {
            RequiredSetting setting = finding.setting();
            description.append("\n    ")
                    .append(pad(setting.variable(), width))
                    .append("  ")
                    .append(problemText(finding))
                    .append("\n    ")
                    .append(" ".repeat(width))
                    .append("  needs ")
                    .append(setting.requirement())
                    .append("\n    ")
                    .append(" ".repeat(width))
                    .append("  (sets ")
                    .append(setting.property())
                    .append(")\n");
        }

        return description.toString();
    }

    /**
     * What is wrong, in the words that distinguish the three cases a developer actually
     * hits: never set, set to nothing, or set to the wrong kind of thing.
     */
    private String problemText(Finding finding) {
        return switch (finding.problem()) {
            case MISSING -> "is not set";
            // The common one: .env.example ships `AUTOX_JWT_SECRET=` empty on purpose, so
            // a straight copy leaves it blank and "is not set" would be a lie.
            case BLANK -> "is set but empty";
            case MALFORMED -> "is set to \"" + finding.actualValue() + "\", which is not valid";
            // Not missing, but refused all the same: a remote database the driver would accept
            // any certificate for. The fix is two URL parameters, so the message gives them.
            case UNVERIFIED_TLS -> finding.actualValue() + ", which does not check the server's"
                    + " certificate. Its parameters must be " + DatabaseTls.REQUIRED
                    + ", replacing any sslmode it has (docs/deployment.md, step 1, \"The JDBC URL\")";
        };
    }

    /**
     * The fix, which depends on whether a {@code .env} exists. Telling someone to create a
     * file they already have is how a good error message becomes a confusing one.
     */
    private String action(MissingConfigurationException cause) {
        if (cause.isDotenvFileFound()) {
            return """
                    Edit the .env at the repository root and give the settings above a value, then
                    start again:

                        cd backend && mvn spring-boot:run

                    A real environment variable overrides .env, so check for one of those too if a
                    value in the file appears to be ignored.
                    """;
        }

        // No .env is also the normal state of a deployed container, where the file's
        // instructions would send an operator looking for something that should not exist.
        // So the deployment case comes first, in one line.
        return """
                On a server or in a container: set each variable above in the service's
                environment (on Render: the service's Environment page) and redeploy. There is
                no .env there, by design. docs/deployment.md lists every variable.

                On a development machine: no .env file was found. Looked in:

                %s

                From a clean checkout:

                    cp .env.example .env     # then fill in the two secrets it leaves blank
                    docker compose up -d     # PostgreSQL on 5433, MinIO on 9000
                    cd backend && mvn spring-boot:run

                .env is read by both docker compose and the application, and is gitignored.
                Settings already present in the real environment take precedence over it, so
                CI and production are unaffected by the file's absence.
                """
                .formatted(DotenvEnvironmentPostProcessor.searchedLocations().stream()
                        .map(location -> "    " + location)
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("    (none)"));
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }
}
