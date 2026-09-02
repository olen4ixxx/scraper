package org.example.flightsearch.collector;

import org.example.flightsearch.common.model.Airline;

/**
 * Thrown when the address we collect from has stopped existing - a 404 where the endpoint used to
 * be, which for an API whose version sits in its path means the version has moved on.
 *
 * <p>WizzAir's did, from 29.12.0 to 29.14.0, and it cost nine days. A 404 is not a refusal and
 * was not treated as one, so every fare request came back empty and every empty answer read as
 * "this route has no flights". The run walked all 2,489 routes, collected nothing, and reported
 * success four times a day.
 *
 * <p>So it stops the run and names the version, because nothing can be collected until the path
 * is corrected and no amount of retrying will change that.
 */
public class ApiMovedException extends CollectionStoppedException {
    public ApiMovedException(Airline airline, String endpoint) {
        super(String.format(
            "%s answered 404 for %s. The API version is part of that path and is enforced, so this "
                + "means they have deployed a new one and every request will keep failing until it "
                + "is updated. Find the current version and set it - nothing can be collected "
                + "meanwhile.", airline, endpoint));
    }
}
