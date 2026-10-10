package com.autox.scheduling;

import static org.assertj.core.api.Assertions.assertThat;

import com.autox.support.AbstractIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Exclusion between instances, tested with threads.
 *
 * <p>Two threads in one JVM are not two deployed instances, but they are the same thing as
 * far as the lock is concerned: each takes its own connection from the pool, and a
 * PostgreSQL advisory lock is held per connection, not per process. What this does prove is
 * the part that would actually break — that the second caller is turned away instead of
 * blocking, and that the lock is released afterwards rather than leaking for the lifetime
 * of the connection.
 */
class ScheduledTaskCoordinatorIntegrationTest extends AbstractIntegrationTest {

    @Autowired ScheduledTaskCoordinator coordinator;

    @Test
    @DisplayName("a second caller is turned away while the first holds the lock")
    void secondCallerSkipsTheRun() throws Exception {
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = threads.submit(() -> coordinator.runExclusively("test-exclusion", () -> {
                runs.incrementAndGet();
                insideFirst.countDown();
                try {
                    releaseFirst.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));

            assertThat(insideFirst.await(30, TimeUnit.SECONDS)).isTrue();

            // The contended call must return rather than queue: a sweep that waits for the
            // other instance to finish and then repeats its work is the failure mode here,
            // not an improvement on it.
            Future<Boolean> second = threads.submit(() -> coordinator.runExclusively("test-exclusion",
                    runs::incrementAndGet));
            assertThat(second.get(30, TimeUnit.SECONDS)).isFalse();
            assertThat(runs.get()).isEqualTo(1);

            releaseFirst.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    @DisplayName("the lock is released once the work is done, so the next run proceeds")
    void lockIsReleasedAfterwards() {
        AtomicInteger runs = new AtomicInteger();

        assertThat(coordinator.runExclusively("test-release", runs::incrementAndGet)).isTrue();
        assertThat(coordinator.runExclusively("test-release", runs::incrementAndGet)).isTrue();
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("the lock is released even when the work fails")
    void lockIsReleasedAfterAFailure() {
        // Without the finally block this is where the lock would leak, and the sweeper
        // would never run again for as long as the connection lived — a failure that looks
        // like "the job stopped" with nothing in the log to say why.
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                coordinator.runExclusively("test-failure", () -> {
                    throw new IllegalStateException("storage is down");
                }))).isInstanceOf(IllegalStateException.class);

        AtomicInteger runs = new AtomicInteger();
        assertThat(coordinator.runExclusively("test-failure", runs::incrementAndGet)).isTrue();
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("different tasks do not block each other")
    void differentTasksAreIndependent() throws Exception {
        CountDownLatch insideFirst = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> expiry = threads.submit(() -> coordinator.runExclusively("task-a", () -> {
                insideFirst.countDown();
                try {
                    releaseFirst.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }));

            assertThat(insideFirst.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(coordinator.runExclusively("task-b", () -> { })).isTrue();

            releaseFirst.countDown();
            assertThat(expiry.get(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }
    }
}
