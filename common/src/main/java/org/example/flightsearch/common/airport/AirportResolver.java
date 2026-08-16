package org.example.flightsearch.common.airport;

import org.example.flightsearch.common.model.Airport;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves real airport metadata (name/city/country/coordinates) by IATA code
 * from a bundled reference dataset. Never fabricates data for unknown codes -
 * callers must handle {@link Optional#empty()} instead of inventing placeholders.
 */
public class AirportResolver {
    private static final String RESOURCE_PATH = "/airports/airports.csv";

    private final Map<String, Airport> byIata;

    public AirportResolver() {
        this.byIata = load();
    }

    public Optional<Airport> resolve(String iata) {
        if (iata == null || iata.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byIata.get(iata.toUpperCase()));
    }

    /**
     * Every code in the reference dataset. Airlines that publish no route list of their own
     * have to be asked about specific airports, and this is the candidate set to ask about.
     */
    public Set<String> knownIataCodes() {
        return Collections.unmodifiableSet(byIata.keySet());
    }

    private static Map<String, Airport> load() {
        Map<String, Airport> result = new ConcurrentHashMap<>();
        try (InputStream in = AirportResolver.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                throw new IllegalStateException("Airport reference dataset not found on classpath: " + RESOURCE_PATH);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null) {
                    throw new IllegalStateException("Airport reference dataset is empty: " + RESOURCE_PATH);
                }
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.split(",", -1);
                    if (parts.length != 6) {
                        throw new IllegalStateException("Malformed row in " + RESOURCE_PATH + ": " + line);
                    }
                    String iata = parts[0].trim().toUpperCase();
                    Airport airport = new Airport(
                        null,
                        iata,
                        parts[1].trim(),
                        parts[2].trim(),
                        parts[3].trim(),
                        Double.valueOf(parts[4].trim()),
                        Double.valueOf(parts[5].trim())
                    );
                    result.put(iata, airport);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load airport reference dataset: " + RESOURCE_PATH, e);
        }
        return result;
    }
}
