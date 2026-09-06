package org.example.flightsearch.collector.wizz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where a run looks when WizzAir's API version moves out from under it.
 *
 * <p>The version is part of the path and is enforced, and it moved three times in a fortnight.
 * Nobody publishes the new number to anything that is not a browser, so the only way to find it is
 * to ask for versions until one answers - and the risk of a search like that is not that it is
 * slow, it is that it misses. A grid that steps past the version actually being served looks
 * exactly like WizzAir having disappeared, and the run stops for a reason that is not true.
 *
 * <p>So the two moves that have actually happened are pinned down here, along with the cost, which
 * is what stops the grid from being widened until it takes an hour.
 */
class WizzVersionSearchTest {

    @Test
    @DisplayName("the search covers the moves WizzAir has actually made")
    void coversTheRealMoves() {
        // 29.12.0 -> 29.14.0 was two minors, and 29.14.0 -> 29.15.1 one minor and a patch.
        assertTrue(WizzCollector.candidates(29, 12, 0).contains("29.14.0"),
            "the jump that cost nine days of collection has to be inside the search");
        assertTrue(WizzCollector.candidates(29, 14, 0).contains("29.15.1"),
            "so does the one after it");
    }

    @Test
    @DisplayName("the nearest versions are tried first")
    void triesTheNearestFirst() {
        List<String> tries = WizzCollector.candidates(29, 15, 1);

        assertEquals("29.15.2", tries.get(0),
            "the next patch is the likeliest move and should cost one request, not nineteen");
        assertTrue(tries.indexOf("29.16.0") < tries.indexOf("30.0.0"),
            "a minor step is likelier than a major one and should be reached sooner");
    }

    @Test
    @DisplayName("the search stays short enough to be worth making")
    void staysAffordable() {
        // Every candidate is a request through the same two-second pace as everything else, so the
        // grid is a time budget as much as a guess: nineteen is about forty seconds, paid once.
        List<String> tries = WizzCollector.candidates(29, 15, 1);

        assertEquals(19, tries.size());
        assertEquals(tries.size(), tries.stream().distinct().count(),
            "asking the same version twice buys nothing and costs two seconds");
    }

    @Test
    @DisplayName("a patch near the end of its minor still looks into the next one")
    void stepsOverAMinorBoundary() {
        // 29.15.9 is not a special case to the grid: the patches ahead run past where the minor
        // will roll over, and the minors ahead cover the other side of it.
        List<String> tries = WizzCollector.candidates(29, 15, 9);

        assertTrue(tries.contains("29.16.0"), "the next minor's first patch is the likely landing");
        assertTrue(tries.contains("29.15.10"), "and the patch line keeps going as well");
    }
}
