package org.example.flightsearch.common.currency;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Converting a stored fare into the currency every price on the site is shown in.
 *
 * <p>Only the answers that can be decided without asking the European Central Bank are pinned
 * here. Rates move daily and a test that asserted one would be wrong by tomorrow and would fail
 * on a machine with no network - so the live path is left to the collectors, which exercise it on
 * every pass. What is worth holding is the shape of the contract around it: euros pass through
 * untouched, and anything unconvertible says so rather than guessing.
 */
class EurConverterTest {

    private final EurConverter converter = new EurConverter();

    @Test
    @DisplayName("euros pass through unchanged, and without asking anyone")
    void eurosAreAlreadyEuros() {
        // Every Ryanair, Transavia, Vueling and Volotea row takes this path, so it has to be
        // exact rather than a conversion by a rate of one.
        assertEquals(Optional.of(49.99), converter.toEur(49.99, "EUR"));
        assertEquals(Optional.of(0.0), converter.toEur(0.0, "EUR"));
        assertEquals(Optional.of(49.99), converter.toEur(49.99, "eur"), "however it is spelled");
    }

    @Test
    @DisplayName("a fare with no currency is not assumed to be in euros")
    void missingCurrencyIsNotAssumed() {
        // Assuming would put a number on the site with the wrong unit beside it, which is worse
        // than leaving the fare out: 809 zloty shown as 809 euros is not a near miss.
        assertTrue(converter.toEur(809.0, null).isEmpty());
        assertTrue(converter.toEur(809.0, "").isEmpty());
        assertTrue(converter.toEur(809.0, "   ").isEmpty());
    }

    @Test
    @DisplayName("a currency nobody publishes a rate for is left out rather than guessed at")
    void unknownCurrencyIsLeftOut() {
        assertTrue(converter.toEur(100.0, "XYZ").isEmpty());
    }
}
