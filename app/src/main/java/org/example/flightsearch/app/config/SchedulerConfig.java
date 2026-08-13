package org.example.flightsearch.app.config;

import org.example.flightsearch.app.service.CollectionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "collector.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerConfig {
    private static final Logger logger = LoggerFactory.getLogger(SchedulerConfig.class);
    
    private final CollectionService collectionService;
    
    public SchedulerConfig(CollectionService collectionService) {
        this.collectionService = collectionService;
    }
    
    // Daily update at 2 AM UTC
    @Scheduled(cron = "0 0 2 * * ?")
    public void dailyUpdate() {
        logger.info("Starting scheduled daily data update");
        try {
            collectionService.collectAll();
            logger.info("Scheduled daily data update completed successfully");
        } catch (Exception e) {
            logger.error("Scheduled daily data update failed", e);
        }
    }
}
