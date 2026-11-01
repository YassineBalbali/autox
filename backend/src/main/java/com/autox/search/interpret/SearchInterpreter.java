package com.autox.search.interpret;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Turns a visitor's description of a car into the filter fields of {@link InterpretationPrompt}'s
 * schema. One method, so a provider is one class and one bean, like {@code EmailSender} and
 * {@code ObjectStorage}.
 *
 * <p><b>What comes back is untrusted.</b> It is whatever a language model produced, and
 * {@link InterpretationValidator} checks every value of it before anything reaches a client.
 * An implementation only has to return the JSON object the model wrote; it does not, and must
 * not, interpret it.
 *
 * <p>Only the query text is sent. No user data, no listing data: the model translates text into
 * filters and never sees, or touches, the database.
 */
public interface SearchInterpreter {

    /** Whether the feature is configured. {@code false} hides it from clients. */
    boolean available();

    /**
     * The model's answer for one query, as the JSON object it wrote.
     *
     * @throws InterpreterUnavailableException on a timeout, an exhausted quota or any other
     *         provider failure — never anything else, so the caller has one case to handle
     */
    JsonNode interpret(String query);
}
