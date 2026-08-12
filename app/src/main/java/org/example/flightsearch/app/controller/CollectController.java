package org.example.flightsearch.app.controller;

import org.example.flightsearch.app.service.CollectionService;
import org.example.flightsearch.common.model.Airline;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/collect")
public class CollectController {
    
    private final CollectionService collectionService;
    
    public CollectController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }
    
    @PostMapping("/wizz")
    public ResponseEntity<String> collectWizz() {
        collectionService.collectAirline(Airline.WIZZAIR);
        return ResponseEntity.ok("WizzAir collection started");
    }
    
    @PostMapping("/ryanair")
    public ResponseEntity<String> collectRyanair() {
        collectionService.collectAirline(Airline.RYANAIR);
        return ResponseEntity.ok("Ryanair collection started");
    }
    
    @PostMapping("/all")
    public ResponseEntity<String> collectAll() {
        collectionService.collectAll();
        return ResponseEntity.ok("Collection for all airlines started");
    }
}
