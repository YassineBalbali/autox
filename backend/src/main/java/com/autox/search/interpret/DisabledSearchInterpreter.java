package com.autox.search.interpret;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * {@code autox.ai.provider=none}, the default: natural-language search is off. Clients are told
 * so by {@code GET /api/v1/search/capabilities} and hide the entry; a request that arrives
 * anyway is answered 404.
 */
@Component
@ConditionalOnProperty(name = "autox.ai.provider", havingValue = AiProperties.NONE, matchIfMissing = true)
public class DisabledSearchInterpreter implements SearchInterpreter {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public JsonNode interpret(String query) {
        throw new InterpreterUnavailableException(
                InterpreterUnavailableException.Reason.DISABLED, "Natural-language search is not configured.");
    }
}
