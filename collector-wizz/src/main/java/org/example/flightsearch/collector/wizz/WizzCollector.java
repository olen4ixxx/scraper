package org.example.flightsearch.collector.wizz;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.flightsearch.collector.AirlineCollector;
import org.example.flightsearch.common.dto.FlightDto;
import org.example.flightsearch.common.dto.RouteDto;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public class WizzCollector implements AirlineCollector {
    private static final Logger logger = LoggerFactory.getLogger(WizzCollector.class);
    private static final String API_BASE = "https://api.wizzair.com";
    private static final ObjectMapper mapper = new ObjectMapper();
    
    private final WebClient webClient;
    
    public WizzCollector(WebClient webClient) {
        this.webClient = webClient;
    }
    
    @Override
    public Airline airline() {
        return Airline.WIZZAIR;
    }
    
    @Override
    public List<RouteDto> loadRoutes() {
        logger.info("Loading WizzAir routes from WAW and WMI...");
        List<RouteDto> routes = new ArrayList<>();
        
        // Known Wizz Air routes from Warsaw (WAW and WMI)
        // This is a manual list since the public API requires authentication
        String[][] knownRoutes = {
            {"WAW", "LTN"}, {"WAW", "LUT"}, {"WAW", "BRS"}, {"WAW", "BOH"}, {"WAW", "LGW"},
            {"WAW", "STN"}, {"WAW", "MAN"}, {"WAW", "LPL"}, {"WAW", "BHX"}, {"WAW", "EMA"},
            {"WAW", "BUD"}, {"WAW", "OTP"}, {"WAW", "CLJ"}, {"WAW", "TSR"}, {"WAW", "SOF"},
            {"WAW", "TLV"}, {"WAW", "AMM"}, {"WAW", "DXB"}, {"WAW", "AUH"}, {"WAW", "DOH"},
            {"WAW", "BCN"}, {"WAW", "VLC"}, {"WAW", "AGP"}, {"WAW", "ALC"}, {"WAW", "PMI"},
            {"WAW", "MLA"}, {"WAW", "FCO"}, {"WAW", "CIA"}, {"WAW", "MXP"}, {"WAW", "LIN"},
            {"WAW", "BGO"}, {"WAW", "OSL"}, {"WAW", "TRD"}, {"WAW", "AAL"}, {"WAW", "CPH"},
            {"WAW", "KEF"}, {"WAW", "RIX"}, {"WAW", "TLL"}, {"WAW", "VNO"}, {"WAW", "KUN"},
            {"WAW", "KIV"}, {"WAW", "TIA"}, {"WAW", "PRN"}, {"WAW", "SKP"}, {"WAW", "SPU"},
            {"WAW", "ZAG"}, {"WAW", "LJU"}, {"WAW", "BEG"}, {"WAW", "SJJ"}, {"WAW", "TIV"},
            {"WAW", "POZ"}, {"WAW", "GDN"}, {"WAW", "WRO"}, {"WAW", "KRK"}, {"WAW", "KTW"},
            {"WAW", "RZE"}, {"WAW", "LUZ"}, {"WAW", "IEV"}, {"WAW", "KBP"}, {"WAW", "ODS"}
        };
        
        for (String[] route : knownRoutes) {
            routes.add(new RouteDto(null, Airline.WIZZAIR, route[0], route[1]));
        }
        
        logger.info("Loaded {} WizzAir routes from WAW/WMI", routes.size());
        return routes;
    }
    
    @Override
    public List<FlightDto> loadFlights(RouteDto route) {
        logger.info("Loading WizzAir flights for route {} -> {}", route.fromAirport(), route.toAirport());
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDateTime now = LocalDateTime.now();
        
        // Wizz Air API requires specific headers and cookies
        // Since it's locked down, we'll return empty for now
        // In production, you would need to use their official API with proper credentials
        logger.warn("Wizz Air API requires authentication - skipping flight loading for route {} -> {}", 
            route.fromAirport(), route.toAirport());
        
        return List.of();
    }
    
    private List<FlightDto> parseFlights(JsonNode root) {
        List<FlightDto> flights = new ArrayList<>();
        
        JsonNode outboundFlights = root.path("outboundFlights");
        if (outboundFlights.isArray()) {
            for (JsonNode flightNode : outboundFlights) {
                try {
                    JsonNode fare = flightNode.path("fares").path(0);
                    double price = fare.path("price").path("amount").asDouble();
                    String currency = fare.path("price").path("currencyCode").asText();
                    
                    String departureDateTime = flightNode.path("departureDateTime").asText();
                    String arrivalDateTime = flightNode.path("arrivalDateTime").asText();
                    String flightNumber = flightNode.path("flightNumber").asText();
                    
                    DateTimeFormatter formatter = DateTimeFormatter.ISO_DATE_TIME;
                    LocalDateTime departure = LocalDateTime.parse(departureDateTime, formatter);
                    LocalDateTime arrival = LocalDateTime.parse(arrivalDateTime, formatter);
                    
                    flights.add(new FlightDto(flightNumber, departure, arrival, price, currency));
                } catch (Exception e) {
                    logger.warn("Failed to parse flight: {}", e.getMessage());
                }
            }
        }
        
        return flights;
    }
}
