package com.autox.assistant;

import com.autox.search.interpret.AiProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No language model is configured ({@code autox.ai.provider=none}, the default): the assistant is
 * not available, whatever {@code AUTOX_ASSISTANT_ENABLED} says. Chosen by an explicit value, as the
 * smart search's interpreter is, rather than by which beans happen to exist.
 */
@Component
@ConditionalOnProperty(name = "autox.ai.provider", havingValue = AiProperties.NONE, matchIfMissing = true)
public class DisabledAssistantPlanner implements AssistantPlanner {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public Answer plan(Input input) {
        throw new PlannerUnavailableException("No assistant planner is configured.", null);
    }
}
