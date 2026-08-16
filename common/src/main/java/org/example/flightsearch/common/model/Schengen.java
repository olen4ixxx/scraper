package org.example.flightsearch.common.model;

import java.util.Locale;
import java.util.Set;

/**
 * Which countries a traveller can change planes in without clearing immigration and needing a
 * separate visa - the practical question behind a self-transfer connection, and the reason a
 * cheap itinerary through a country you cannot enter is worth nothing.
 *
 * <p>Names are matched against the airport reference data as spelled there. Bulgaria and
 * Romania are included: both completed their accession in January 2025. Cyprus and Ireland
 * are not - Ireland has never joined, and Cyprus is still awaiting full accession.
 */
public final class Schengen {

    private static final Set<String> COUNTRIES = Set.of(
        "austria", "belgium", "bulgaria", "croatia", "czech republic", "denmark", "estonia",
        "finland", "france", "germany", "greece", "hungary", "iceland", "italy", "latvia",
        "liechtenstein", "lithuania", "luxembourg", "malta", "netherlands", "norway", "poland",
        "portugal", "romania", "slovakia", "slovenia", "spain", "sweden", "switzerland"
    );

    private Schengen() {
    }

    public static boolean includes(String country) {
        return country != null && COUNTRIES.contains(country.trim().toLowerCase(Locale.ROOT));
    }
}
