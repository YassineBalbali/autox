package com.autox.config.env;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.boot.env.EnvironmentPostProcessor;

/**
 * The {@code .env} reader. A plain unit test: parsing is a pure function over lines, and
 * the file search is a question about paths.
 */
class DotenvEnvironmentPostProcessorTest {

    // --- registration -----------------------------------------------------

    @Test
    @DisplayName("both startup hooks are registered where Spring Boot actually looks")
    void hooksAreRegistered() {
        // This is here because getting it wrong is silent. The hooks were first registered
        // in META-INF/spring/org.springframework.boot.env.EnvironmentPostProcessor.imports,
        // which looks plausible and is ignored: the .imports mechanism is ImportCandidates
        // and serves @AutoConfiguration only. EnvironmentPostProcessor and FailureAnalyzer
        // both come from spring.factories. There was no warning — the application simply
        // started with neither hook, and failed exactly as it had before.
        String factories;
        try (var stream = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            assertThat(stream).as("META-INF/spring.factories must be on the classpath").isNotNull();
            factories = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new AssertionError("Could not read META-INF/spring.factories", e);
        }

        // Declared under the keys SpringFactoriesLoader actually reads...
        assertThat(factories)
                .contains(EnvironmentPostProcessor.class.getName())
                .contains(DotenvEnvironmentPostProcessor.class.getName())
                .contains(RequiredConfigurationEnvironmentPostProcessor.class.getName())
                .contains(FailureAnalyzer.class.getName())
                .contains(MissingConfigurationFailureAnalyzer.class.getName());

        // ...and nothing left behind in the file that does not work. An .imports file for
        // these types is inert, so its presence would mean someone had re-introduced the
        // mistake alongside the fix.
        assertThat(getClass().getClassLoader().getResource(
                        "META-INF/spring/" + EnvironmentPostProcessor.class.getName() + ".imports"))
                .as(".imports does not work for EnvironmentPostProcessor; spring.factories does")
                .isNull();
    }

    @Test
    @DisplayName("Spring Boot can construct the dotenv loader with the argument it injects")
    void loaderIsConstructible() {
        // The loader takes a DeferredLogFactory, which Boot resolves reflectively. A
        // constructor Boot cannot satisfy is a startup failure, not a compile error, so it
        // is worth proving here rather than discovering it on the next person's machine.
        DotenvEnvironmentPostProcessor loader =
                new DotenvEnvironmentPostProcessor(new org.springframework.boot.logging.DeferredLogs());

        assertThat(loader.getOrder())
                .as("must run before Boot reads application.yml, so a profile set in .env applies")
                .isLessThan(org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER);
        assertThat(new RequiredConfigurationEnvironmentPostProcessor().getOrder())
                .as("must run after everything, so it sees the final configuration")
                .isGreaterThan(loader.getOrder());
    }

    // --- parsing ----------------------------------------------------------

    @Test
    @DisplayName("reads KEY=value, ignoring blank lines and whole-line comments")
    void readsSimpleAssignments() {
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "# a comment",
                "",
                "AUTOX_DB_USERNAME=autox",
                "   ",
                "  # an indented comment",
                "AUTOX_PORT=8081"));

        assertThat(entries).containsExactly(
                Map.entry("AUTOX_DB_USERNAME", "autox"),
                Map.entry("AUTOX_PORT", "8081"));
    }

    @Test
    @DisplayName("tolerates an export prefix and Windows line endings")
    void tolerantOfShellAndWindowsHabits() {
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "export AUTOX_JWT_ISSUER=https://api.autox.local",
                "AUTOX_S3_BUCKET=autox-images\r"));

        assertThat(entries).containsEntry("AUTOX_JWT_ISSUER", "https://api.autox.local");
        // The \r would otherwise end up inside the value and produce a bucket name that
        // looks identical in every log line and matches nothing.
        assertThat(entries).containsEntry("AUTOX_S3_BUCKET", "autox-images");
    }

    @Test
    @DisplayName("strips surrounding quotes but nothing inside them")
    void stripsSurroundingQuotes() {
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "DOUBLE=\"a value\"",
                "SINGLE='a value'",
                "INNER=\"has \"quotes\" inside\"",
                "UNBALANCED=\"only at the start"));

        assertThat(entries).containsEntry("DOUBLE", "a value");
        assertThat(entries).containsEntry("SINGLE", "a value");
        assertThat(entries).containsEntry("INNER", "has \"quotes\" inside");
        // Not a quoted value, so it is left exactly as typed rather than half-stripped.
        assertThat(entries).containsEntry("UNBALANCED", "\"only at the start");
    }

    @Test
    @DisplayName("a hash inside a value is part of the value, not a comment")
    void doesNotTreatHashAsATrailingComment() {
        // The whole reason inline comments are unsupported. `p@ss#word` is a password, and
        // there is no way to tell it from a password followed by a comment. Guessing wrong
        // produces an authentication failure that looks like a wrong password in the
        // database rather than a parsing rule in this file.
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "AUTOX_DB_PASSWORD=p@ss#word",
                "AUTOX_S3_SECRET_KEY=abc # not a comment either"));

        assertThat(entries).containsEntry("AUTOX_DB_PASSWORD", "p@ss#word");
        assertThat(entries).containsEntry("AUTOX_S3_SECRET_KEY", "abc # not a comment either");
    }

    @Test
    @DisplayName("an empty value is kept, so it can be reported as set-but-empty")
    void keepsEmptyValues() {
        // .env.example ships `AUTOX_JWT_SECRET=` deliberately blank, so this is the state
        // of every freshly copied file. Dropping the key would make the failure message
        // say "is not set" about a line the developer is looking straight at.
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "AUTOX_JWT_SECRET=",
                "AUTOX_S3_PUBLIC_BASE_URL="));

        assertThat(entries).containsEntry("AUTOX_JWT_SECRET", "");
        assertThat(entries).containsEntry("AUTOX_S3_PUBLIC_BASE_URL", "");
    }

    @Test
    @DisplayName("values are taken literally — no variable interpolation")
    void noInterpolation() {
        // docker compose would expand this, which makes it a real difference between the
        // two readers of the file. Supporting it badly is worse than not supporting it;
        // nothing in .env.example uses it, and the Javadoc says so.
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "AUTOX_DB_URL=jdbc:postgresql://localhost:${POSTGRES_PORT}/autox"));

        assertThat(entries)
                .containsEntry("AUTOX_DB_URL", "jdbc:postgresql://localhost:${POSTGRES_PORT}/autox");
    }

    @Test
    @DisplayName("lines that are not assignments are skipped rather than guessed at")
    void skipsNonAssignments() {
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "this is not an assignment",
                "=novalue",
                "GOOD=yes"));

        assertThat(entries).containsExactly(Map.entry("GOOD", "yes"));
    }

    @Test
    @DisplayName("a later line wins, as a shell would behave")
    void lastAssignmentWins() {
        Map<String, Object> entries = DotenvEnvironmentPostProcessor.parse(List.of(
                "AUTOX_PORT=8080",
                "AUTOX_PORT=8081"));

        assertThat(entries).containsEntry("AUTOX_PORT", "8081");
    }

    // --- where it looks ---------------------------------------------------

    @Test
    @DisplayName("looks in the working directory and up to the repository root, no further")
    void searchStopsAtTheRepositoryRoot() {
        List<Path> candidates = DotenvEnvironmentPostProcessor.candidates();

        // Tests run from backend/, and the file lives one level up at the repository root.
        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().getParent().getFileName())
                .as("the nearest candidate is the working directory")
                .hasToString("backend");

        Path repositoryRoot = candidates.getLast().getParent();
        assertThat(repositoryRoot.resolve(".git"))
                .as("the search stops at the directory holding .git")
                .exists();

        // The hazard this guards: plenty of developers have a ~/.env for something else,
        // and configuring this application from it would be a genuinely hard afternoon.
        Path home = Path.of(System.getProperty("user.home"));
        assertThat(candidates)
                .as("must never reach the home directory")
                .noneMatch(candidate -> candidate.getParent().equals(home));
    }

    @Test
    @DisplayName("in a git worktree, where .git is a file, the search still stops at its root")
    void searchStopsAtAWorktreeRoot(@TempDir Path home) throws IOException {
        // The layout that reached ~/.env: a worktree created beside the clone, in the home
        // directory, with someone else's .env sitting in that home directory.
        Files.writeString(home.resolve(".env"), "AUTOX_JWT_SECRET=not-this-application\n");
        Path worktree = Files.createDirectory(home.resolve("autox-pr15"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + home.resolve("autox/.git/worktrees/autox-pr15") + "\n");
        Path backend = Files.createDirectory(worktree.resolve("backend"));

        assertThat(DotenvEnvironmentPostProcessor.candidates(backend))
                .as("the backend, then the worktree root and no further")
                .containsExactly(backend.resolve(".env"), worktree.resolve(".env"));
    }
}
