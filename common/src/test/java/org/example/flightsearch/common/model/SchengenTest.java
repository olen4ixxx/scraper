package org.example.flightsearch.common.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which countries a Schengen visa already covers.
 *
 * <p>This decides whether an itinerary is offered at all when "change planes inside Schengen only"
 * is on, which it is by default. Getting a country wrong in either direction is quiet: too strict
 * and a perfectly good connection never appears, too loose and the search offers a trip through a
 * country the traveller cannot enter.
 *
 * <p>The list is matched against country names as the airport dataset spells them, so the cases
 * below are the spellings that actually occur there rather than the ones a list of member states
 * would use.
 */
class SchengenTest {

    @Test
    @DisplayName("member states are covered")
    void membersAreIncluded() {
        assertTrue(Schengen.includes("Poland"));
        assertTrue(Schengen.includes("Spain"));
        assertTrue(Schengen.includes("Netherlands"));
        assertTrue(Schengen.includes("Croatia"));
        assertTrue(Schengen.includes("Switzerland"), "in Schengen without being in the EU");
        assertTrue(Schengen.includes("Norway"), "likewise");
        assertTrue(Schengen.includes("Iceland"));
    }

    @Test
    @DisplayName("countries outside it are not, however close they are")
    void nonMembersAreExcluded() {
        assertFalse(Schengen.includes("United Kingdom"), "the reason this filter exists at all");
        assertFalse(Schengen.includes("Ireland"), "in the EU, outside Schengen");
        assertFalse(Schengen.includes("Turkiye"));
        assertFalse(Schengen.includes("Morocco"));
        assertFalse(Schengen.includes("Albania"));
        assertFalse(Schengen.includes("Georgia"));
        assertFalse(Schengen.includes("Egypt"));
    }

    @Test
    @DisplayName("spelling and spacing in the dataset do not decide the answer")
    void matchesRegardlessOfCase() {
        assertTrue(Schengen.includes("POLAND"));
        assertTrue(Schengen.includes("poland"));
        assertTrue(Schengen.includes("  Poland  "));
    }

    @Test
    @DisplayName("an unknown or missing country is treated as outside")
    void unknownIsOutside() {
        // Being cautious here costs a connection; being generous offers a trip that cannot be
        // taken. An airport whose country we cannot name is not somewhere to route people through.
        assertFalse(Schengen.includes(null));
        assertFalse(Schengen.includes(""));
        assertFalse(Schengen.includes("   "));
        assertFalse(Schengen.includes("Atlantis"));
    }
}
