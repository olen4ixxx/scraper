package org.example.flightsearch.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableAsync
@EnableTransactionManagement
@ComponentScan(basePackages = {
    "org.example.flightsearch.app",
    "org.example.flightsearch.search",
    "org.example.flightsearch.api",
    "org.example.flightsearch.collector",
    "org.example.flightsearch.db"
})
public class Application {
    
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
