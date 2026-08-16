package org.example.flightsearch.app.config;

import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.collector.ryanair.RyanairCollector;
import org.example.flightsearch.collector.transavia.TransaviaCollector;
import org.example.flightsearch.collector.vueling.VuelingCollector;
import org.example.flightsearch.collector.wizz.WizzCollector;
import org.example.flightsearch.common.airport.AirportResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

@Configuration
public class CollectorConfig {

    @Bean
    public List<AirlineCollector> collectors(WebClient webClient, AirportResolver airportResolver) {
        return List.of(
            new WizzCollector(webClient),
            new RyanairCollector(webClient),
            new VuelingCollector(webClient, airportResolver),
            new TransaviaCollector(webClient, airportResolver)
        );
    }

    @Bean
    public AirportResolver airportResolver() {
        return new AirportResolver();
    }
}
