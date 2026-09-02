package org.example.flightsearch.collector;

/**
 * Thrown when there is no point continuing a run: the site is not going to answer, whatever the
 * remaining routes are.
 *
 * <p>The distinction that matters is between this and an answer we don't like. A route with no
 * flights, a pair they don't fly, a fare that hasn't moved - those are answers, and a run absorbs
 * them and carries on. This is for the other kind: being turned away, or asking at an address
 * that no longer exists. Carrying on then collects nothing, and does it thousands of times.
 *
 * <p>Ending the run loudly is the point. Both of the long silences this project has had - WizzAir
 * standing still for nine days behind a green job, Transavia for longer - were failures that
 * looked like ordinary empty answers, and the cost was entirely in not being told.
 */
public abstract class CollectionStoppedException extends RuntimeException {
    protected CollectionStoppedException(String message) {
        super(message);
    }
}
