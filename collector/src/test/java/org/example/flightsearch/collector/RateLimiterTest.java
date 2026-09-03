package org.example.flightsearch.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pace collection runs at, and how it reacts to being pushed back.
 *
 * <p>The behaviour worth pinning is the relationship between widening and decay rather than
 * either number alone. Tripling on refusal while giving back 30% on success meant every refusal
 * needed three and a half successes to undo, so any refusal rate above one in five ratcheted the
 * interval upwards for good - a Transavia scan reached 32 seconds a request that way and never
 * came back down, which put its scan 34 hours from finishing.
 */
class RateLimiterTest {

    @Test
    @DisplayName("one answer undoes one refusal, so the interval tracks the site rather than drifting")
    void widenAndDecayAreMirrorImages() {
        RateLimiter limiter = new RateLimiter(1000);
        assertEquals(1000, limiter.currentIntervalMillis());

        limiter.backOff();
        limiter.backOff();
        assertEquals(4000, limiter.currentIntervalMillis());

        limiter.recovered();
        limiter.recovered();
        assertEquals(1000, limiter.currentIntervalMillis(), "two answers should undo two refusals exactly");
    }

    @Test
    @DisplayName("a steady drizzle of refusals does not ratchet the interval upwards")
    void aRefusalRateBelowHalfDoesNotRunAway() {
        RateLimiter limiter = new RateLimiter(1000);
        // One refusal in three, which is worse than anything seen in practice.
        for (int i = 0; i < 300; i++) {
            if (i % 3 == 0) {
                limiter.backOff();
            } else {
                limiter.recovered();
            }
        }
        assertEquals(1000, limiter.currentIntervalMillis(),
            "with more answers than refusals the interval should settle back at the base");
    }

    @Test
    @DisplayName("the interval has a ceiling, because past it a run is dead rather than slow")
    void widensOnlyToTheCeiling() {
        RateLimiter limiter = new RateLimiter(1000);
        for (int i = 0; i < 50; i++) {
            limiter.backOff();
        }
        assertEquals(16000, limiter.currentIntervalMillis(), "base x 16 for a base this size");

        // A small base still gets a usable backoff rather than a proportionally tiny one.
        RateLimiter brisk = new RateLimiter(250);
        for (int i = 0; i < 50; i++) {
            brisk.backOff();
        }
        assertEquals(10000, brisk.currentIntervalMillis(), "the floor under the ceiling");
    }

    @Test
    @DisplayName("decay never takes the interval below the pace it started at")
    void decayStopsAtTheBase() {
        RateLimiter limiter = new RateLimiter(500);
        for (int i = 0; i < 20; i++) {
            limiter.recovered();
        }
        assertEquals(500, limiter.currentIntervalMillis());
    }

    @Test
    @DisplayName("concurrent callers are spaced out rather than let through together")
    void spacesOutConcurrentCallers() throws Exception {
        // The point of the limiter: eight route threads share one, and the site sees one
        // request per interval regardless of how many threads are asking at once. Collection
        // runs exactly this way, and getting it wrong is how WizzAir was asked at three times
        // the rate it allows.
        int callers = 12;
        long interval = 25;
        RateLimiter limiter = new RateLimiter(interval);

        long startedAt = System.currentTimeMillis();
        try (ExecutorService threads = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                threads.submit(limiter::acquire);
            }
            threads.shutdown();
            assertTrue(threads.awaitTermination(30, TimeUnit.SECONDS), "the limiter should not deadlock");
        }
        long elapsed = System.currentTimeMillis() - startedAt;

        // The first caller goes straight through, so eleven gaps remain.
        long leastPossible = (callers - 1) * interval;
        assertTrue(elapsed >= leastPossible,
            "twelve callers at " + interval + "ms apart cannot finish in " + elapsed + "ms");
    }
}
