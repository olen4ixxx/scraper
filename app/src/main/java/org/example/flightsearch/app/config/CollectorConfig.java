package org.example.flightsearch.app.config;

import org.example.flightsearch.collector.AirlineCollector;
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
     * The version WizzAir currently serves, which is part of the path and is enforced: any other
     * value answers 404 to every request. They move it every few weeks - 29.12.0 to 29.14.0 to
     * 29.15.1 within a fortnight - so it is a setting rather than a literal, and a 404 stops the
     * run with a message saying exactly this instead of being read as "no flights on this route".
     *
     * <p>Not discovered automatically, though it is written on their home page. That page answers
     * 405 to this client and serves itself only to a browser, so reading it would mean pretending
     * to be one, which is the line this project does not cross for Transavia's Cloudflare
     * challenge or easyJet's either. Finding the number by hand once a month is the cheaper honesty.
     */
    @Bean
    public List<AirlineCollector> collectors(WebClient webClient, AirportResolver airportResolver,
                                              EurConverter eurConverter,
                                              @Value("${collector.wizz.api-version:29.15.1}") String wizzApiVersion) {
        return List.of(
            new WizzCollector(webClient, airportResolver, eurConverter, wizzApiVersion),
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
