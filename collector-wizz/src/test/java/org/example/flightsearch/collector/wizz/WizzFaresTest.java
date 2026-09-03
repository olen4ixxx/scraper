package org.example.flightsearch.collector.wizz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.currency.EurConverter;
import org.example.flightsearch.common.dto.FlightDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a WizzAir fare chart, and refusing the entries that are not fares.
 *
 * <p>Their fare chart answers for every day in the window whether or not anything flies, and a day
 * with no flight comes back as an entry like any other - marked "noData" and carrying an amount of
 * zero. Taken at face value those read as free flights, which is how 1,692 fares of nothing
 * reached the database on the first run and 13,167 by the time it was noticed. They sorted to the
 * top of every cheapest-first search.
 */
class WizzFaresTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 1);
    private static final LocalDate HORIZON = TODAY.plusDays(60);

    private final WizzCollector collector =
        new WizzCollector(null, new AirportResolver(), new EurConverter(), "29.14.0");

    private static JsonNode chart(String outboundFlights) throws Exception {
        return MAPPER.readTree("{\"outboundFlights\":[" + outboundFlights + "]}");
    }

    private static String fare(String date, String priceType, double amount, String currency) {
        return String.format("{\"price\":{\"amount\":%s,\"currencyCode\":\"%s\"},"
            + "\"priceType\":\"%s\",\"date\":\"%sT00:00:00\"}", amount, currency, priceType, date);
    }

    @Test
    @DisplayName("a real fare is kept, in the currency they quoted")
    void keepsRealFares() throws Exception {
        List<FlightDto> flights = collector.parseFares(
            chart(fare("2026-09-20", "price", 809.0, "PLN")), TODAY, HORIZON);

        assertEquals(1, flights.size());
        assertEquals(809.0, flights.get(0).price());
        assertEquals("PLN", flights.get(0).currency(),
            "stored as quoted - converting here would freeze it at today's exchange rate");
    }

    @Test
    @DisplayName("a day with no flight is not a free flight")
    void dropsNoDataDays() throws Exception {
        List<FlightDto> flights = collector.parseFares(
            chart(fare("2026-09-20", "noData", 0.0, "PLN")), TODAY, HORIZON);

        assertTrue(flights.isEmpty(), "\"noData\" means nothing flies that day, not that it is free");
    }

    @Test
    @DisplayName("an amount of zero is refused even when it claims to be a price")
    void dropsZeroAmounts() throws Exception {
        List<FlightDto> flights = collector.parseFares(
            chart(fare("2026-09-20", "price", 0.0, "PLN")), TODAY, HORIZON);

        assertTrue(flights.isEmpty(), "no airline sells a seat for nothing");
    }

    @Test
    @DisplayName("fares outside the window we asked about are left out")
    void dropsFaresOutsideTheWindow() throws Exception {
        // The chart answers for the days either side of the one asked about, so it returns dates
        // beyond the horizon as a matter of course.
        List<FlightDto> flights = collector.parseFares(chart(
            fare("2026-08-30", "price", 500.0, "PLN") + "," + fare("2027-01-15", "price", 500.0, "PLN")),
            TODAY, HORIZON);

        assertTrue(flights.isEmpty());
    }

    @Test
    @DisplayName("one unreadable entry does not cost the rest of the chart")
    void survivesAMalformedEntry() throws Exception {
        List<FlightDto> flights = collector.parseFares(chart(
            "{\"price\":null,\"priceType\":\"price\",\"date\":\"2026-09-20T00:00:00\"},"
                + "{\"priceType\":\"price\",\"date\":\"2026-09-21T00:00:00\"},"
                + fare("2026-09-22", "price", 299.0, "PLN")),
            TODAY, HORIZON);

        assertEquals(1, flights.size(), "the good fare should still come through");
        assertEquals(299.0, flights.get(0).price());
    }

    @Test
    @DisplayName("an empty or missing chart yields nothing rather than failing")
    void handlesAnEmptyChart() throws Exception {
        assertTrue(collector.parseFares(chart(""), TODAY, HORIZON).isEmpty());
        assertTrue(collector.parseFares(MAPPER.readTree("{}"), TODAY, HORIZON).isEmpty());
        assertTrue(collector.parseFares(null, TODAY, HORIZON).isEmpty());
    }
}
