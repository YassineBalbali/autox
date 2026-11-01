package com.autox.search.interpret;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Natural-language search configuration, bound from {@code autox.ai.*}.
 *
 * @param provider  which {@link SearchInterpreter} to use: {@code none} (the default, and the
 *                  feature is off and hidden) or {@code groq}. Selected the way the mail
 *                  provider is, by an explicit value rather than by which beans exist.
 * @param apiKey    the provider's key. No default; required at startup when the provider is
 *                  {@code groq} (see {@code RequiredSetting}).
 * @param model     the provider's model id. See docs/ai-search.md for why this one.
 * @param baseUrl   the provider's OpenAI-compatible API root. Configurable so a test can point
 *                  the real client at a local server, not so production can move it.
 * @param timeout   how long to wait for an answer before giving up and telling the visitor
 *                  to use the ordinary search. The whole point of the fallback is that a
 *                  slow provider costs one feature, not the page.
 * @param rateLimit the per-address cap, stricter than the public read limit: each request
 *                  spends a shared, free quota.
 */
@ConfigurationProperties(prefix = "autox.ai")
public record AiProperties(
        String provider,
        String apiKey,
        String model,
        String baseUrl,
        Duration timeout,
        RateLimit rateLimit) {

    public static final String NONE = "none";
    public static final String GROQ = "groq";

    public AiProperties {
        provider = orDefault(provider, NONE).strip().toLowerCase(java.util.Locale.ROOT);
        model = orDefault(model, "openai/gpt-oss-120b");
        baseUrl = orDefault(baseUrl, "https://api.groq.com/openai/v1");
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(5) : timeout;
        rateLimit = rateLimit == null ? new RateLimit(true, 0, null) : rateLimit;
    }

    /**
     * Requests allowed per client address in a fixed window, which opens with the first request
     * and closes {@code window} later; over the limit, refused until it closes.
     *
     * <p>Ten in ten minutes: a visitor rephrasing a search a few times is well inside it, and
     * a script spending the quota on someone else's behalf is stopped after ten.
     */
    public record RateLimit(boolean enabled, int requestsPerWindow, Duration window) {

        public RateLimit {
            requestsPerWindow = requestsPerWindow <= 0 ? 10 : requestsPerWindow;
            window = positiveOr(window, Duration.ofMinutes(10));
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
