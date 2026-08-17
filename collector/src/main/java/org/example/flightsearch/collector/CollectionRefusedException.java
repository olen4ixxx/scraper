package org.example.flightsearch.collector;

import org.example.flightsearch.common.model.Airline;

/**
 * Thrown when a site has stopped answering us, to end the run loudly instead of letting it walk
 * the remaining routes collecting nothing and finish green.
 *
 * <p>Failing is the useful outcome here. A run that is being refused has nothing to gain from the
 * next thousand requests and the site has already said as much; carrying on is both pointless and
 * rude. More importantly, a scheduled job that goes red is a job someone looks at, whereas the
 * silent version of this cost days - two airlines appeared to be collecting normally, on schedule,
 * while their tables stood still.
 */
public class CollectionRefusedException extends RuntimeException {
    public CollectionRefusedException(Airline airline, int consecutiveRefusals, String lastError) {
        super(String.format(
            "%s refused %d requests in a row, so this run is being stopped rather than continued "
                + "against a site that has stopped answering. Last refusal: %s",
            airline, consecutiveRefusals, lastError));
    }
}
