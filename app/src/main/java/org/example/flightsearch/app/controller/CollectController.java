package org.example.flightsearch.app.controller;

import org.example.flightsearch.app.service.CollectionService;
import org.example.flightsearch.common.model.Airline;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;

/**
 * Collection is triggered from outside rather than on a timer, so the scheduled GitHub Actions
 * run decides when it happens. Not registered at all when collection is switched off, which is
 * how the public deployment avoids exposing it.
 */
@RestController
@RequestMapping("/collect")
@ConditionalOnProperty(name = "collector.enabled", havingValue = "true", matchIfMissing = true)
public class CollectController {

    private final CollectionService collectionService;

    public CollectController(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    /**
     * @param rediscoverRoutes re-scan the airline's network instead of reusing the one already
     *                         stored. Off by default because discovery is the expensive half for
     *                         some airlines and their networks change with the season; the weekly
     *                         run turns it on.
     */
    @PostMapping("/{airline}")
    public ResponseEntity<String> collect(@PathVariable String airline,
                                          @RequestParam(defaultValue = "false") boolean rediscoverRoutes) {
        if ("all".equalsIgnoreCase(airline)) {
            collectionService.collectAll(rediscoverRoutes);
            return ResponseEntity.ok("Collection finished for all airlines");
        }

        return parse(airline)
            .map(parsed -> {
                collectionService.collectAirline(parsed, rediscoverRoutes);
                return ResponseEntity.ok(parsed + " collection finished");
            })
            .orElseGet(() -> ResponseEntity.badRequest().body(
                "Unknown airline '" + airline + "'. Known: " + Arrays.toString(Airline.values()) + " or 'all'"));
    }

    private java.util.Optional<Airline> parse(String airline) {
        return Arrays.stream(Airline.values())
            .filter(known -> known.name().equalsIgnoreCase(airline))
            .findFirst();
    }
}
