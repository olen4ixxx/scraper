package org.example.flightsearch.collector;

import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;

import java.util.List;

public interface AirlineCollector {
    
    Airline airline();
    
    List<RouteDto> loadRoutes();
    
    List<FlightDto> loadFlights(RouteDto route);
}
