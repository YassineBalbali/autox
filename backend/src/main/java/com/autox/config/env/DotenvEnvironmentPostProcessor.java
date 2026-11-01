package com.autox.config.env;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * Loads {@code .env} into the Spring environment, so the application starts from a clean
 * terminal without anyone exporting anything.
 *
 * <p>{@code docker compose} reads {@code .env} by itself; nothing else does. So
 * {@code mvn spring-boot:run} inherited none of it and failed on an unconfigured
 * datasource, through a hundred and fifty lines of stack trace ending in
 * {@code 'url' must start with "jdbc"} — which names neither the file nor the variable.
 * One file, read by both, fixes that.
 *
 * <h2>Precedence</h2>
 *
 * The source is inserted <b>immediately below the real environment variables</b>:
 *
 * <pre>
 *   system properties  >  OS environment  >  .env  >  application.yml  >  defaults
 * </pre>
 *
 * which is the only ordering that makes {@code .env} safe. A real environment variable
 * always wins, so CI, containers and production behave exactly as they did — in those
 * places the file does not exist at all and this class is a no-op. And {@code .env} sits
 * above {@code application.yml}, so it behaves like the export it replaces rather than like
 * a weaker kind of default.
 *
 * <p>It is a {@link SystemEnvironmentPropertySource} rather than a plain map on purpose:
 * that is what gives the entries the same relaxed binding real environment variables get,
 * so {@code SPRING_PROFILES_ACTIVE} or {@code AUTOX_LOG_LEVEL} in the file do what a
 * developer expects instead of being silently inert.
 *
 * <h2>Why not spring-dotenv</h2>
 *
 * The obvious library, {@code me.paulschwarz:spring-dotenv}, was last released in May 2023,
 * registers a {@code SpringApplicationRunListener} that cannot be scoped or ordered, and
 * exposes values under an {@code env.} prefix — which would mean rewriting every
 * {@code ${AUTOX_*}} placeholder in the production configuration in order to fix a local
 * development convenience. Changing how production resolves its configuration is a poor
 * trade for seventy lines, and the fail-fast check in
 * {@link RequiredConfigurationEnvironmentPostProcessor} needs this precedence to be
 * something we can state and test.
 */
public class DotenvEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** The name of the property source this adds, so it is identifiable in diagnostics. */
    public static final String PROPERTY_SOURCE_NAME = "dotenvFile";

    static final String FILE_NAME = ".env";

    /**
     * A backstop on how far up to look. The search normally stops at the repository root
     * (see {@link #candidates()}); this only bounds the case where there is no {@code .git}
     * to find, such as an extracted source archive.
     */
    static final int MAX_PARENTS = 3;

    private final Log log;

    /**
     * Boot injects the deferred log factory. Logging is not configured this early, so a
     * normal logger would write into the void — a deferred one replays once it is.
     */
    public DotenvEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(DotenvEnvironmentPostProcessor.class);
    }

    /**
     * Just before Boot reads {@code application.yml}, so a profile or a log level set in
     * {@code .env} is in force by the time the configuration files are parsed.
     */
    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER - 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = locate();
        if (file == null) {
            // Not an error, and not worth a warning: no .env is the normal state in CI, in
            // a container and in production. RequiredConfigurationEnvironmentPostProcessor
            // is what complains, and only if something is actually unconfigured.
            log.debug("No " + FILE_NAME + " found in " + searchedLocations() + "; "
                    + "configuration comes from the environment alone.");
            return;
        }

        Map<String, Object> entries;
        try {
            entries = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            // Readable-but-unparseable is worth failing on: the alternative is starting
            // with a configuration the developer believes they have supplied.
            throw new IllegalStateException("Could not read " + file.toAbsolutePath(), e);
        }

        if (entries.isEmpty()) {
            log.warn(file.toAbsolutePath() + " contains no settings.");
            return;
        }

        MutablePropertySources sources = environment.getPropertySources();
        SystemEnvironmentPropertySource source =
                new SystemEnvironmentPropertySource(PROPERTY_SOURCE_NAME, entries);

        if (sources.contains(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME)) {
            sources.addAfter(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, source);
        } else {
            sources.addLast(source);
        }

        // The names only. Half of these are credentials, and a log line is the last place
        // they should turn up — this is the message that proves the file was found, not a
        // dump of what was in it.
        log.info("Loaded " + entries.size() + " setting(s) from " + file.toAbsolutePath()
                + ": " + String.join(", ", entries.keySet().stream().sorted().toList())
                + " (any that are also real environment variables keep the environment's value)");
    }

    /** The first {@code .env} at or above the working directory, or {@code null}. */
    static Path locate() {
        for (Path candidate : candidates()) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Where to look, nearest first: the working directory, then upward <b>as far as the
     * repository root and no further</b>.
     *
     * <p>{@code mvn spring-boot:run} runs in {@code backend/} while the file lives at the
     * repository root, so ascending is necessary. Stopping at the root matters just as
     * much: plenty of developers have a {@code ~/.env} for something else entirely, and
     * silently configuring this application from it would be a genuinely hard afternoon.
     * {@code .git} marks where to stop.
     */
    static List<Path> candidates() {
        return candidates(Paths.get("").toAbsolutePath());
    }

    /**
     * {@link #candidates()} from {@code start} rather than the working directory.
     *
     * <p>{@code .git} is a directory in an ordinary clone and a file in a worktree (or a
     * submodule), a one-line pointer to the real repository. Either marks the root. Looking
     * for the directory alone walked straight past a worktree's root, and a worktree created
     * beside the clone, in the home directory, then had {@code ~/.env} as a candidate.
     */
    static List<Path> candidates(Path start) {
        List<Path> candidates = new ArrayList<>();
        Path directory = start;

        for (int level = 0; level <= MAX_PARENTS && directory != null; level++) {
            candidates.add(directory.resolve(FILE_NAME));
            if (Files.exists(directory.resolve(".git"))) {
                break;
            }
            directory = directory.getParent();
        }

        return candidates;
    }

    /** The paths {@link #locate()} tried, for an error message worth reading. */
    static List<String> searchedLocations() {
        return candidates().stream().map(Path::toString).toList();
    }

    /**
     * Parses the subset of {@code .env} syntax that is unambiguous.
     *
     * <p>Supported: {@code KEY=value}, blank lines, whole-line {@code #} comments, an
     * optional {@code export} prefix, and surrounding single or double quotes. Windows line
     * endings are tolerated, because this file is edited on Windows.
     *
     * <p><b>Not</b> supported, deliberately:
     *
     * <ul>
     *   <li><b>Trailing comments.</b> {@code PASSWORD=a#b} is a password containing a hash,
     *       not a password followed by a comment, and there is no way to tell the two apart
     *       without quoting rules nobody will remember. Treating {@code #} as a comment
     *       only at the start of a line means a value is always exactly what was typed.</li>
     *   <li><b>Variable interpolation</b> ({@code ${OTHER}}). docker compose supports it,
     *       which makes this a real difference between the two readers of this file — but
     *       supporting it badly is worse than not supporting it, and nothing in
     *       {@code .env.example} uses it.</li>
     * </ul>
     *
     * <p>An empty value is kept rather than dropped, so {@code AUTOX_JWT_SECRET=} is
     * reported as present-but-blank. That is the single most likely mistake after
     * {@code cp .env.example .env}, because the example ships that line empty on purpose.
     */
    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> entries = new LinkedHashMap<>();

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring("export ".length()).strip();
            }

            int separator = line.indexOf('=');
            if (separator <= 0) {
                // A line with no key, or no '=' at all. Skipping beats guessing, and the
                // loaded-settings log line makes an omission visible.
                continue;
            }

            String key = line.substring(0, separator).strip();
            String value = unquote(line.substring(separator + 1).strip());
            entries.put(key, value);
        }

        return entries;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
