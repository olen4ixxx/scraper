package org.example.flightsearch.common.model;

import java.util.List;

/**
 * Polish airports actually served by Ryanair, verified against Ryanair's own
 * public routes API (not guessed) - see collector-ryanair for the discovery logic.
 */
public final class PolandAirports {
    public static final List<String> ALL = List.of(
        "WAW", "WMI", "KRK", "GDN", "WRO", "POZ", "KTW", "RZE", "LUZ", "SZZ", "BZG"
    );

    private PolandAirports() {}
}
