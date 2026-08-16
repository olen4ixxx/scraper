package org.example.flightsearch.api;

import org.example.flightsearch.common.model.PolandAirports;
import org.example.flightsearch.db.entity.AirportEntity;
import org.example.flightsearch.db.repository.AirportRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class MetadataController {

    private final AirportRepository airportRepository;

    public MetadataController(AirportRepository airportRepository) {
        this.airportRepository = airportRepository;
    }

    @GetMapping("/api/destinations")
    public List<Destination> destinations() {
        List<Destination> destinations = new ArrayList<>();
        for (AirportEntity a : airportRepository.findDestinationsFrom(PolandAirports.ALL)) {
            destinations.add(new Destination(a.iata(), a.name(), a.city(), a.country(), a.lat(), a.lon()));
        }
        return destinations;
    }

    public record Destination(
        String iata,
        String name,
        String city,
        String country,
        Double lat,
        Double lon
    ) {}
}
