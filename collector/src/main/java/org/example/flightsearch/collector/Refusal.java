package org.example.flightsearch.collector;

import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Tells a refusal apart from an answer.
 *
 * <p>This distinction is the difference between collecting data and quietly collecting nothing.
 * Every one of these sites answers "we don't fly that" as a normal, meaningful response - a 404,
 * an empty list - and a collector has to record that as fact. What it must not do is treat being
 * turned away the same way: a 503 from WizzAir's fare chart says nothing whatsoever about whether
 * that route has flights, and filing it as "no fares" writes an airline's whole network down to
 * nothing while the run reports success.
 *
 * <p>That is not hypothetical. A scheduled WizzAir run walked all 1,113 known routes, was refused
 * on 421 of its first 429 requests, saved nothing, and finished green. A Transavia run spent 63
 * minutes doing the same and reported success with an empty network.
 *
 * <p>The statuses here are the ones that mean "not now" rather than "not ever": too many requests,
 * forbidden (how Transavia turns down a burst), and the gateway family (how WizzAir does).
 */
public final class Refusal {
    private Refusal() {
    }

    public static boolean is(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof WebClientResponseException http) {
                return switch (http.getStatusCode().value()) {
                    case 403, 429, 502, 503, 504 -> true;
                    default -> false;
                };
            }
        }
        return false;
    }
}
