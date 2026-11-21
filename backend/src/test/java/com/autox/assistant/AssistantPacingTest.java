package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pace of all callers together, in tokens as the provider counts them, so the server says
 * "busy" before Groq does (docs/assistant-plan.md, 5.3 and 12).
 */
class AssistantPacingTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
    private final AssistantPacing pacing = new AssistantPacing(
            new AssistantProperties(true, null, null, 0, 7_000, null, false, null), clock);

    @Test
    @DisplayName("reserves each call at what calls have cost lately, and refuses one that would not fit the minute")
    void tokens() {
        AssistantPacing.Reservation first = pacing.reserve();
        assertThat(first.granted()).isTrue();
        pacing.settle(first.call(), 3_000);

        clock.advance(Duration.ofSeconds(10));
        AssistantPacing.Reservation second = pacing.reserve();
        assertThat(second.granted()).isTrue();
        pacing.settle(second.call(), 3_000);
        assertThat(pacing.estimate()).isEqualTo(3_000);

        // 6 000 spent in the minute, 3 000 more expected: over 7 000 until the first is a minute old.
        clock.advance(Duration.ofSeconds(5));
        AssistantPacing.Reservation third = pacing.reserve();
        assertThat(third.granted()).isFalse();
        assertThat(third.waitSeconds()).isEqualTo(46);

        clock.advance(Duration.ofSeconds(46));
        assertThat(pacing.reserve().granted()).isTrue();
    }

    @Test
    @DisplayName("waits for the minute's room when the wait fits the bound, and refuses with the wait beyond it")
    void reserveWithin() {
        java.util.List<Duration> slept = new java.util.ArrayList<>();
        AssistantPacing waiting = new AssistantPacing(new AssistantProperties(true, null, null, 0, 7_000, null, false, null),
                clock, duration -> {
                    slept.add(duration);
                    clock.advance(duration);
                });
        waiting.settle(waiting.reserve().call(), 3_000);
        waiting.settle(waiting.reserve().call(), 3_000);

        // 6 000 in the minute, 3 000 expected: 61 s to wait, more than 30.
        AssistantPacing.Reservation refused = waiting.reserveWithin(Duration.ofSeconds(30));
        assertThat(refused.granted()).isFalse();
        assertThat(refused.waitSeconds()).isEqualTo(61);
        assertThat(slept).isEmpty();

        // 40 s later the same wait is 21 s, within 30: it waits, then goes ahead.
        clock.advance(Duration.ofSeconds(40));
        AssistantPacing.Reservation granted = waiting.reserveWithin(Duration.ofSeconds(30));
        assertThat(granted.granted()).isTrue();
        assertThat(slept).containsExactly(Duration.ofSeconds(21));
    }

    @Test
    @DisplayName("starts from an estimate, and lets a first call through whatever it is expected to cost")
    void firstCall() {
        assertThat(pacing.estimate()).isEqualTo(AssistantPacing.FIRST_ESTIMATE);
        AssistantPacing small = new AssistantPacing(new AssistantProperties(true, null, null, 0, 500, null, false, null), clock);
        assertThat(small.reserve().granted()).isTrue();
        assertThat(small.reserve().granted()).isFalse();
    }

    @Test
    @DisplayName("keeps a call's reservation when the provider did not count it")
    void uncounted() {
        AssistantPacing.Reservation call = pacing.reserve();
        pacing.settle(call.call(), 0);
        assertThat(pacing.estimate()).isEqualTo(AssistantPacing.FIRST_ESTIMATE);
    }

    @Test
    @DisplayName("sends nothing before the time the provider gave in its 429")
    void providerBusy() {
        pacing.providerBusyFor(17);

        AssistantPacing.Reservation refused = pacing.reserve();
        assertThat(refused.granted()).isFalse();
        assertThat(refused.waitSeconds()).isEqualTo(18);

        clock.advance(Duration.ofSeconds(17));
        assertThat(pacing.reserve().granted()).isTrue();
    }
}
