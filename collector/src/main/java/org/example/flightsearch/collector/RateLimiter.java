package org.example.flightsearch.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spaces out calls to at most one per interval, shared across every caller regardless of how
 * many threads are calling concurrently - a simple leaky bucket: each acquire() reserves the
 * next free slot and sleeps only as long as needed to reach it.
 *
 * <p>The interval widens when a site pushes back. Transavia showed why this matters: a few
 * thousand probes at four per second went from a 0.4% refusal rate to refusing four requests
 * in five, and the obvious response - retrying immediately - doubles the pressure at exactly
 * the moment they are asking for less of it. Backing off and letting the interval decay again
 * once requests start succeeding is what they are actually asking for.
 */
public class RateLimiter {
    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);
    private static final double WIDEN_BY = 3.0;
    private static final double DECAY_BY = 0.7;

    private final long baseIntervalMillis;
    private final long maxIntervalMillis;

    private long currentIntervalMillis;
    private long nextAllowedTime;

    public RateLimiter(long baseIntervalMillis) {
        this(baseIntervalMillis, Math.max(baseIntervalMillis * 60, 60_000));
    }

    public RateLimiter(long baseIntervalMillis, long maxIntervalMillis) {
        this.baseIntervalMillis = baseIntervalMillis;
        this.maxIntervalMillis = maxIntervalMillis;
        this.currentIntervalMillis = baseIntervalMillis;
    }

    public synchronized void acquire() {
        long now = System.currentTimeMillis();
        long waitUntil = Math.max(now, nextAllowedTime);
        nextAllowedTime = waitUntil + currentIntervalMillis;
        long sleepMs = waitUntil - now;
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Call when a request is refused for being too frequent - a 403 or 429 rather than a real answer. */
    public synchronized void backOff() {
        long widened = Math.min((long) (currentIntervalMillis * WIDEN_BY), maxIntervalMillis);
        if (widened != currentIntervalMillis) {
            logger.info("Backing off: waiting {}ms between requests instead of {}ms", widened, currentIntervalMillis);
        }
        currentIntervalMillis = widened;
    }

    /** Call after a request succeeds, so a burst of refusals doesn't slow everything down forever. */
    public synchronized void recovered() {
        if (currentIntervalMillis <= baseIntervalMillis) {
            return;
        }
        currentIntervalMillis = Math.max(baseIntervalMillis, (long) (currentIntervalMillis * DECAY_BY));
    }

    public synchronized long currentIntervalMillis() {
        return currentIntervalMillis;
    }
}
