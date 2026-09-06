package org.example.flightsearch.app.config;

import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.ApiVersionStore;
import org.example.flightsearch.collector.ryanair.RyanairCollector;
import org.example.flightsearch.collector.transavia.TransaviaCollector;
import org.example.flightsearch.collector.volotea.VoloteaCollector;
import org.example.flightsearch.collector.vueling.VuelingCollector;
import org.example.flightsearch.collector.wizz.WizzCollector;
import org.example.flightsearch.common.airport.AirportResolver;
import org.example.flightsearch.common.currency.EurConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Configuration
public class CollectorConfig {

    @Bean
    public EurConverter eurConverter() {
        return new EurConverter();
    }

    /**
     * Where WizzAir's API version starts from, not where it stays. The version is part of the path
     * and is enforced - any other value answers 404 - and they move it every few weeks: 29.12.0 to
     * 29.14.0 to 29.15.1 within a fortnight.
     *
     * <p>So this is only the first guess. A run that meets a 404 searches for the version now being
     * served and records it, and every run after that starts from what was found rather than from
     * this number; the setting matters just once, on a database that has never seen a version.
     *
     * <p>The search asks their API, which is the honest way round. The number is also printed on
     * their home page, but that page answers 405 to this client and serves itself only to a
     * browser, so reading it would mean pretending to be one - the line this project does not cross
     * for Transavia's Cloudflare challenge or easyJet's Akamai either.
     */
    @Bean
    public List<AirlineCollector> collectors(WebClient webClient, AirportResolver airportResolver,
                                              EurConverter eurConverter, ApiVersionStore apiVersions,
                                              @Value("${collector.wizz.api-version:29.15.1}") String wizzApiVersion) {
        return List.of(
            new WizzCollector(webClient, airportResolver, eurConverter, wizzApiVersion, apiVersions),
            new RyanairCollector(webClient),
            new VuelingCollector(webClient, airportResolver),
            new TransaviaCollector(webClient, airportResolver),
            new VoloteaCollector(webClient, airportResolver)
        );
    }

    @Bean
    public AirportResolver airportResolver() {
        return new AirportResolver();
    }
}
