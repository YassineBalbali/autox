package com.autox.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The minute's turns for every caller together: the free tier's tokens per minute (docs/assistant-plan.md, 5.3). */
class AssistantPacingTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-19T10:00:00Z"));
    private final AssistantLimits limits = new AssistantLimits(null,
            new AssistantProperties(true, null, null, 0, 3, null), clock);

    @Test
    @DisplayName("lets three turns through in a minute, then says how long until the first is a minute old")
    void threeAMinute() {
        assertThat(limits.takeTurn()).isEmpty();
        clock.advance(Duration.ofSeconds(10));
        assertThat(limits.takeTurn()).isEmpty();
        assertThat(limits.takeTurn()).isEmpty();

        OptionalLong wait = limits.takeTurn();
        assertThat(wait).hasValue(51);

        clock.advance(Duration.ofSeconds(50));
        assertThat(limits.takeTurn()).isEmpty();
        assertThat(limits.takeTurn()).isPresent();
    }
}
