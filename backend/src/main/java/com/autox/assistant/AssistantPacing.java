package com.autox.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The pace of all callers together, kept below the provider's own (docs/assistant-plan.md, 5.3), so
 * that a busy minute is answered by the server at once rather than by a refused, still-counted call.
 *
 * <p><b>Counted in tokens, as Groq counts.</b> Each planner call reserves what a call has cost lately
 * (the average of the last few, from the provider's own counts), and is settled with what it really
 * cost. A call goes ahead only if the last minute's tokens and its reservation fit in
 * {@code autox.assistant.tokens-per-minute}, which sits below the free tier's 8 000 so the smart
 * search's calls fit too. <b>And Groq's word is final:</b> after a 429 nothing is sent before the
 * time it gave.
 *
 * <p>In memory: the API runs as one instance on Render, and a restart forgets a minute at most.
 */
@Component
public class AssistantPacing {

    /** What a call is reserved at before any has been measured: the trimmed prompt plus a short plan. */
    static final long FIRST_ESTIMATE = 2_000;
    static final int AVERAGE_OVER = 5;
    private static final Duration MINUTE = Duration.ofMinutes(1);

    /** How the caller waits: the thread sleeps; a test's advances its clock. */
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final AssistantProperties properties;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Deque<Call> lastMinute = new ArrayDeque<>();
    private final Deque<Long> lastCosts = new ArrayDeque<>();
    private Instant providerBusyUntil = Instant.MIN;

    @Autowired
    public AssistantPacing(AssistantProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AssistantPacing(AssistantProperties properties, Clock clock) {
        this(properties, clock, duration -> Thread.sleep(duration.toMillis()));
    }

    AssistantPacing(AssistantProperties properties, Clock clock, Sleeper sleeper) {
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** One planner call's place in the minute. */
    public static final class Call {
        private final Instant at;
        private long tokens;

        private Call(Instant at, long tokens) {
            this.at = at;
            this.tokens = tokens;
        }
    }

    /** @param call null when there is a wait; {@code waitSeconds} then says how long */
    public record Reservation(Call call, long waitSeconds) {
        public boolean granted() {
            return call != null;
        }
    }

    public synchronized Reservation reserve() {
        Instant now = clock.instant();
        if (now.isBefore(providerBusyUntil)) {
            return new Reservation(null, secondsUntil(now, providerBusyUntil));
        }
        forgetBefore(now.minus(MINUTE));
        long estimate = estimate();
        long spent = lastMinute.stream().mapToLong(call -> call.tokens).sum();
        long limit = properties.tokensPerMinute();
        if (!lastMinute.isEmpty() && spent + estimate > limit) {
            // Wait until enough of the minute's calls are a minute old for this one to fit.
            long freed = 0;
            for (Call call : lastMinute) {
                freed += call.tokens;
                if (spent - freed + estimate <= limit) {
                    return new Reservation(null, secondsUntil(now, call.at.plus(MINUTE)));
                }
            }
            return new Reservation(null, secondsUntil(now, lastMinute.peekLast().at.plus(MINUTE)));
        }
        Call call = new Call(now, estimate);
        lastMinute.addLast(call);
        return new Reservation(call, 0);
    }

    /**
     * A reservation, waiting for the minute's room while the wait fits in {@code max}: for the
     * turn's second planner call, which a pace already granted to its first should not cancel. A
     * wait beyond {@code max}, or one interrupted, is returned refused, with its time.
     */
    public Reservation reserveWithin(Duration max) {
        Instant deadline = clock.instant().plus(max);
        for (;;) {
            Reservation reservation = reserve();
            if (reservation.granted() || clock.instant().plusSeconds(reservation.waitSeconds()).isAfter(deadline)) {
                return reservation;
            }
            try {
                sleeper.sleep(Duration.ofSeconds(reservation.waitSeconds()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return reservation;
            }
        }
    }

    /** The provider's count for a call; a call it did not count keeps its reservation. */
    public synchronized void settle(Call call, long tokens) {
        if (call == null || tokens <= 0) {
            return;
        }
        call.tokens = tokens;
        lastCosts.addLast(tokens);
        while (lastCosts.size() > AVERAGE_OVER) {
            lastCosts.removeFirst();
        }
    }

    /** Groq answered 429: nothing is sent before the time it gave. */
    public synchronized void providerBusyFor(long seconds) {
        Instant until = clock.instant().plusSeconds(seconds);
        if (until.isAfter(providerBusyUntil)) {
            providerBusyUntil = until;
        }
    }

    /** Forgets the minute and any wait: for tests, which share one instance. */
    synchronized void clear() {
        lastMinute.clear();
        lastCosts.clear();
        providerBusyUntil = Instant.MIN;
    }

    synchronized long estimate() {
        return lastCosts.isEmpty() ? FIRST_ESTIMATE
                : Math.round(lastCosts.stream().mapToLong(Long::longValue).average().orElse(FIRST_ESTIMATE));
    }

    private void forgetBefore(Instant cutoff) {
        while (!lastMinute.isEmpty() && !lastMinute.peekFirst().at.isAfter(cutoff)) {
            lastMinute.removeFirst();
        }
    }

    private static long secondsUntil(Instant now, Instant then) {
        return Math.max(1, Duration.between(now, then).toSeconds() + 1);
    }
}
