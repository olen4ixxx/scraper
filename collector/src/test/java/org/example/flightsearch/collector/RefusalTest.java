package org.example.flightsearch.collector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The classification that decides whether a run keeps going.
 *
 * <p>This is where nine days went. A 404 was not on the refusal list and so fell through to the
 * branch that reads a failure as "this route has no flights" - which is indistinguishable from a
 * real empty answer, so WizzAir collected nothing behind a green job four times a day after they
 * moved their API version. The statuses below are therefore not a matter of taste: each one is a
 * decision about whether the collector carries on, and getting one wrong is silent.
 */
class RefusalTest {

    private static WebClientResponseException status(int code) {
        return WebClientResponseException.create(
            code, HttpStatus.valueOf(code).getReasonPhrase(), HttpHeaders.EMPTY,
            new byte[0], StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("being turned away is a refusal, whatever form it takes")
    void turnedAwayIsRefusal() {
        // Transavia's answer to a burst, WizzAir's answer to too many requests, and the
        // gateway family that means "not now" rather than "not ever".
        assertTrue(Refusal.is(status(403)));
        assertTrue(Refusal.is(status(429)));
        assertTrue(Refusal.is(status(502)));
        assertTrue(Refusal.is(status(503)));
        assertTrue(Refusal.is(status(504)));
    }

    @Test
    @DisplayName("a missing endpoint is not a refusal - it is a different failure with a different fix")
    void notFoundIsNotRefusal() {
        // Backing off and retrying a 404 would wait politely forever for a path that has gone.
        // WizzAir's collector raises ApiMovedException on it instead, which stops the run and
        // says the version needs updating.
        assertFalse(Refusal.is(status(404)));
    }

    @Test
    @DisplayName("a rejected request is an answer, not a refusal")
    void badRequestIsAnAnswer() {
        // WizzAir answers 400 InvalidArrivalStationCode for a route it has stopped flying. That
        // is the site talking to us, and treating it as a refusal would abandon a run over
        // nothing more than a stale route.
        assertFalse(Refusal.is(status(400)));
    }

    @Test
    @DisplayName("a refusal is recognised however deeply it is wrapped")
    void findsTheCauseThroughWrappers() {
        // Reactor wraps what it throws, so the status is rarely the top-level exception.
        assertTrue(Refusal.is(new RuntimeException("wrapped", new IllegalStateException("deeper", status(503)))));
    }

    @Test
    @DisplayName("a failure with no status at all is not a refusal")
    void nonHttpFailureIsNotRefusal() {
        // A timeout or a DNS failure says nothing about whether the site is turning us away.
        assertFalse(Refusal.is(new java.net.SocketTimeoutException("read timed out")));
        assertFalse(Refusal.is(null));
    }
}
