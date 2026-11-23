package com.autox.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A planner that answers what the test queues, and records what it was given. Stands in for the
 * language model wherever a test is about everything around it: the validator, the tools, the
 * answer, the limits and the failures. No test calls Groq (docs/assistant-plan.md, 9).
 */
class StubAssistantPlanner implements AssistantPlanner {

    static final long TOKENS_IN = 1_800;
    static final long TOKENS_OUT = 120;

    private final Deque<Object> next = new ArrayDeque<>();
    private final List<Input> inputs = new ArrayList<>();

    /** The next plans, in order; each call takes one. */
    synchronized void plans(JsonNode... plans) {
        next.clear();
        next.addAll(List.of(plans));
    }

    /**
     * The next calls, in order: a plan (JsonNode), a failure to throw, or an action to run during
     * the call before taking the next step, such as making the pace busy mid-turn.
     */
    synchronized void steps(Object... steps) {
        next.clear();
        next.addAll(List.of(steps));
    }

    /** What Groq's planner throws when it does not answer within the timeout. */
    static PlannerUnavailableException timeout() {
        return new PlannerUnavailableException(PlannerUnavailableException.Kind.TIMEOUT, "stub: timeout", null, 0);
    }

    /** The next call fails, as a timeout or a spent quota would. */
    synchronized void fail() {
        next.clear();
        next.add(new PlannerUnavailableException("stub: unavailable", null, 50));
    }

    /** The next call is refused for the provider's rate limit, as Groq's 429 would. */
    synchronized void busy(long retryAfterSeconds) {
        next.clear();
        next.add(new PlannerBusyException(retryAfterSeconds));
    }

    /** Groq's 429 for its daily quota. */
    synchronized void dailyQuota(long retryAfterSeconds) {
        next.clear();
        next.add(new PlannerBusyException(retryAfterSeconds, true));
    }

    synchronized List<Input> inputs() {
        return List.copyOf(inputs);
    }

    synchronized void reset() {
        next.clear();
        inputs.clear();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public synchronized Answer plan(Input input) {
        inputs.add(input);
        Object answer = next.pollFirst();
        while (answer instanceof Runnable action) {
            action.run();
            answer = next.pollFirst();
        }
        if (answer instanceof PlannerUnavailableException failure) {
            throw failure;
        }
        if (answer instanceof PlannerBusyException busy) {
            throw busy;
        }
        if (answer == null) {
            throw new IllegalStateException("The test queued no plan for this call");
        }
        return new Answer((JsonNode) answer, TOKENS_IN, TOKENS_OUT);
    }
}
