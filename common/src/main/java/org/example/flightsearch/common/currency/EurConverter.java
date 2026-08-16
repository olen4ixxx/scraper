package org.example.flightsearch.common.currency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Converts fares to euros using the European Central Bank's daily reference rates - published
 * openly, needing no key, and quoted against the euro, which is exactly the direction needed
 * here.
 *
 * <p>Some airlines price in the departure market's own currency and offer no way to ask for
 * another: WizzAir answers in NOK from Stavanger and PLN from Warsaw whatever you send it. The
 * database holds euros throughout, and search adds up the legs of a trip as plain numbers, so
 * a fare has to be converted before it is stored rather than compared against euros as if the
 * figures meant the same thing.
 *
 * <p>An unknown currency yields no value at all. Guessing a rate would put a wrong price in
 * front of someone deciding what to book, which is worse than the fare simply being absent.
 */
public class EurConverter {
    private static final Logger logger = LoggerFactory.getLogger(EurConverter.class);
    private static final String ECB_DAILY_RATES = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml";
    private static final String EUR = "EUR";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    private Map<String, Double> ratesPerEur = Map.of();
    private LocalDate ratesFetchedOn;

    /**
     * @return the amount in euros, or empty if the currency has no published rate - a caller
     *         must drop the fare rather than store the unconverted figure.
     */
    public synchronized Optional<Double> toEur(double amount, String currency) {
        if (currency == null || currency.isBlank()) {
            return Optional.empty();
        }
        if (EUR.equalsIgnoreCase(currency)) {
            return Optional.of(amount);
        }

        ensureRatesLoaded();
        Double perEur = ratesPerEur.get(currency.toUpperCase());
        if (perEur == null || perEur == 0) {
            return Optional.empty();
        }
        // ECB quotes how much of a currency one euro buys, so dividing goes back to euros.
        return Optional.of(amount / perEur);
    }

    private void ensureRatesLoaded() {
        if (ratesFetchedOn != null && ratesFetchedOn.equals(LocalDate.now()) && !ratesPerEur.isEmpty()) {
            return;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(ECB_DAILY_RATES))
                .timeout(Duration.ofSeconds(20))
                .GET()
                .build();
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                logger.warn("Exchange rates unavailable: ECB answered {}", response.statusCode());
                return;
            }

            Map<String, Double> parsed = new HashMap<>();
            var document = DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(response.body()));
            NodeList entries = document.getElementsByTagName("Cube");
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String currency = entry.getAttribute("currency");
                String rate = entry.getAttribute("rate");
                if (!currency.isBlank() && !rate.isBlank()) {
                    parsed.put(currency, Double.parseDouble(rate));
                }
            }

            if (parsed.isEmpty()) {
                logger.warn("Exchange rates unavailable: nothing parsed from the ECB feed");
                return;
            }
            ratesPerEur = parsed;
            ratesFetchedOn = LocalDate.now();
            logger.info("Loaded {} exchange rates from the ECB", parsed.size());
        } catch (Exception e) {
            // Keep whatever was loaded before: a day-old rate beats dropping every fare, and
            // an empty map simply means nothing gets converted until the feed comes back.
            logger.warn("Failed to refresh exchange rates, keeping the previous set: {}", e.getMessage());
        }
    }
}
