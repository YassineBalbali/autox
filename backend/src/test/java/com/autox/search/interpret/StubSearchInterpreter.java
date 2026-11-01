package com.autox.search.interpret;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A provider that answers what the test tells it to, and records what it was asked. Stands in
 * for the language model wherever a test is about everything around it: validation, the
 * endpoint's errors, the rate limit.
 */
class StubSearchInterpreter implements SearchInterpreter {

    private JsonNode next;
    private InterpreterUnavailableException failure;
    private final List<String> queries = new ArrayList<>();

    /** The model's next answer. */
    synchronized void answer(JsonNode answer) {
        this.next = answer;
        this.failure = null;
    }

    /** The provider's next failure. */
    synchronized void fail(InterpreterUnavailableException.Reason reason) {
        this.failure = new InterpreterUnavailableException(reason, "stub: " + reason);
        this.next = null;
    }

    synchronized List<String> queries() {
        return List.copyOf(queries);
    }

    synchronized void reset() {
        next = null;
        failure = null;
        queries.clear();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public synchronized JsonNode interpret(String query) {
        queries.add(query);
        if (failure != null) {
            throw failure;
        }
        if (next == null) {
            throw new IllegalStateException("The test did not say what the stub should answer.");
        }
        return next;
    }
}
