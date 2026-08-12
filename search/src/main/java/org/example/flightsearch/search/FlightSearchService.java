package org.example.flightsearch.search;

import org.example.flightsearch.common.dto.PriceHistoryPoint;
import org.example.flightsearch.common.dto.SearchRequest;
import org.example.flightsearch.common.dto.SearchResult;

import java.util.List;

public interface FlightSearchService {

    List<SearchResult> search(SearchRequest request);

    /** All prices collected for one specific flight over time, oldest first. */
    List<PriceHistoryPoint> getPriceHistory(Long flightId);
}
