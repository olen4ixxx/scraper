package org.example.flightsearch.collector.transavia;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Reading the fares out of whatever shape Transavia sends.
 *
 * <p>They changed it once already, from a bare array to {@code {"data": [...]}}, and the change
 * cost a full pass with nothing to show it: the parser asked whether the root was an array, got
 * no, and returned no fares - which is exactly what a route they don't fly looks like. 332 routes
 * came back empty with no error and no refusal.
 *
 * <p>So both shapes are read, and both are held here. The old one is not dead weight: it is the
 * shape they used until recently and could return again, and a test that only knew today's shape
 * would let the same silence back in.
 */
class TransaviaFaresTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String raw) throws Exception {
        return MAPPER.readTree(raw);
    }

    @Test
    @DisplayName("reads the shape they send today")
    void readsWrappedFares() throws Exception {
        JsonNode fares = TransaviaCollector.fares(json("""
            {"data":[{"date":"2026-08-26","price":113,"type":"regularFare"},
                     {"date":"2026-08-27","price":105,"type":"regularFare"}]}
            """));

        assertNotNull(fares, "the wrapped array is still an array of fares");
        assertEquals(2, fares.size());
        assertEquals(113, fares.get(0).path("price").asInt());
    }

    @Test
    @DisplayName("reads the shape they used to send")
    void readsBareFares() throws Exception {
        JsonNode fares = TransaviaCollector.fares(json("""
            [{"date":"2026-08-26","price":113},{"date":"2026-08-27","price":105}]
            """));

        assertNotNull(fares);
        assertEquals(2, fares.size());
    }

    @Test
    @DisplayName("an empty list is an answer - they fly it, there is just nothing on those days")
    void emptyIsStillAnAnswer() throws Exception {
        assertNotNull(TransaviaCollector.fares(json("{\"data\":[]}")));
        assertEquals(0, TransaviaCollector.fares(json("{\"data\":[]}")).size());
    }

    @Test
    @DisplayName("a shape with no fares in it reads as nothing, not as an empty list")
    void unknownShapeIsNothing() throws Exception {
        // The distinction matters: an empty list means "no flights on these dates", while this
        // means "we could not find the fares at all" - which is what a third change of envelope
        // would look like, and what route discovery must not read as a route they don't fly.
        assertNull(TransaviaCollector.fares(json("{\"error\":\"Invalid route\"}")));
        assertNull(TransaviaCollector.fares(json("{\"data\":{\"unexpected\":true}}")));
        assertNull(TransaviaCollector.fares(null));
    }
}
