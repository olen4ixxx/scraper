package org.example.flightsearch.app.controller;

import org.example.flightsearch.app.service.CollectionService;
import org.example.flightsearch.common.model.Airline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/collect")
@ConditionalOnProperty(name = "collector.enabled", havingValue = "true", matchIfMissing = true)
public class CollectorController {
    private static final Logger logger = LoggerFactory.getLogger(CollectorController.class);
    
    private final CollectionService collectionService;
    
    public CollectorController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }
    
    @PostMapping("/wizz")
    public ResponseEntity<Map<String, Object>> collectWizz() {
        logger.info("Starting Wizz Air collection via API");
        long startTime = System.currentTimeMillis();
        
        try {
            collectionService.collectAirline(Airline.WIZZAIR);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("airline", "WIZZAIR");
            response.put("duration_ms", duration);
            
            logger.info("Wizz Air collection completed via API in {}ms", duration);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Wizz Air collection failed", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("airline", "WIZZAIR");
            response.put("error", e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    @PostMapping("/ryanair")
    public ResponseEntity<Map<String, Object>> collectRyanair() {
        logger.info("Starting Ryanair collection via API");
        long startTime = System.currentTimeMillis();
        
        try {
            collectionService.collectAirline(Airline.RYANAIR);
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("airline", "RYANAIR");
            response.put("duration_ms", duration);
            
            logger.info("Ryanair collection completed via API in {}ms", duration);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("Ryanair collection failed", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("airline", "RYANAIR");
            response.put("error", e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
    
    @PostMapping("/all")
    public ResponseEntity<Map<String, Object>> collectAll() {
        logger.info("Starting collection for all airlines via API");
        long startTime = System.currentTimeMillis();
        
        try {
            collectionService.collectAll();
            long duration = System.currentTimeMillis() - startTime;
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("airlines", "ALL");
            response.put("duration_ms", duration);
            
            logger.info("All airlines collection completed via API in {}ms", duration);
            return ResponseEntity.ok(response);
            
        } catch (Exception e) {
            logger.error("All airlines collection failed", e);
            
            Map<String, Object> response = new HashMap<>();
            response.put("status", "error");
            response.put("airlines", "ALL");
            response.put("error", e.getMessage());
            
            return ResponseEntity.internalServerError().body(response);
        }
    }
}
