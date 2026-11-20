package com.autox.assistant;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Assistant AutoX configuration, bound from {@code autox.assistant.*} (docs/assistant-plan.md, 5).
 *
 * @param enabled           the switch, {@code AUTOX_ASSISTANT_ENABLED}, off by default. Off, both
 *                          endpoints answer 404 and nothing is counted or called. On, it also needs
 *                          a planner: the Groq one, chosen by {@code autox.ai.provider=groq}.
 * @param model             the planner's model id; the smart search's key and provider are reused
 * @param timeout           one planner call, end to end
 * @param dailyTokenBudget  tokens the assistant may spend per Paris day, the planner's own count,
 *                          leaving the rest of the free tier's daily tokens to the smart search
 * @param tokensPerMinute   the planner's tokens per minute, for every visitor together, below the
 *                          free tier's 8 000 so the smart search's calls fit too (AssistantPacing)
 * @param limits            per caller, counted before any spend
 */
@ConfigurationProperties(prefix = "autox.assistant")
public record AssistantProperties(
        boolean enabled,
        String model,
        Duration timeout,
        long dailyTokenBudget,
        long tokensPerMinute,
        Limits limits) {

    public AssistantProperties {
        model = model == null || model.isBlank() ? "openai/gpt-oss-120b" : model;
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(8) : timeout;
        dailyTokenBudget = dailyTokenBudget <= 0 ? 140_000 : dailyTokenBudget;
        tokensPerMinute = tokensPerMinute <= 0 ? 7_000 : tokensPerMinute;
        limits = limits == null ? new Limits(true, 0, null, 0, 0) : limits;
    }

    /**
     * @param perAddress        turns from one address in {@code addressWindow}
     * @param anonymousPerDay   turns a day from one address, without an account
     * @param accountPerDay     turns a day for one signed-in account
     */
    public record Limits(boolean enabled, int perAddress, Duration addressWindow, int anonymousPerDay,
                         int accountPerDay) {

        public Limits {
            perAddress = perAddress <= 0 ? 10 : perAddress;
            addressWindow = addressWindow == null || addressWindow.isNegative() || addressWindow.isZero()
                    ? Duration.ofMinutes(10) : addressWindow;
            anonymousPerDay = anonymousPerDay <= 0 ? 15 : anonymousPerDay;
            accountPerDay = accountPerDay <= 0 ? 40 : accountPerDay;
        }
    }
}
