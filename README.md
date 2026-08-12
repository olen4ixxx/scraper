# Flight Search - Multi-module Gradle Project

Flight search aggregator for WizzAir and Ryanair airlines with Spring Boot 3.5+, PostgreSQL, and modern Java 21 features.

## Features

- Multi-module Gradle architecture
- Spring Boot 3.6+ with Virtual Threads
- PostgreSQL 17 with Flyway migrations
- Spring Data JDBC (no JPA)
- WebClient for HTTP calls
- Scrapes airports and flights from WizzAir and Ryanair APIs
- Finds cheapest routes (direct and with connections)
- REST API for search and collection

## Technology Stack

- Java 21 (with Virtual Threads)
- Gradle (Kotlin DSL)
- Spring Boot 3.6+
- PostgreSQL 17
- Flyway
- Spring Data JDBC
- WebClient
- Jackson

## Project Structure

```
flight-search/
├── app/                    # Main Spring Boot application
├── common/                 # Shared models and DTOs
├── db/                     # Entities and repositories
├── collector/             # Collector interface
├── collector-wizz/        # WizzAir collector
├── collector-ryanair/     # Ryanair collector
├── search/                 # Flight search service
└── api/                    # REST controllers
```

## Setup

1. Start PostgreSQL:
```bash
docker-compose up -d
```

2. Build the project:
```bash
./gradlew build
```

3. Run the application:
```bash
./gradlew :app:bootRun
```

## API Endpoints

### Collection
- `POST /collect/wizz` - Collect WizzAir flights
- `POST /collect/ryanair` - Collect Ryanair flights
- `POST /collect/all` - Collect all airlines

### Search
- `GET /search?from=WAW&to=AGP&date=2026-09-12` - Search flights

## Example Usage

```bash
# Collect WizzAir data
curl -X POST http://localhost:8080/collect/wizz

# Search for flights
curl "http://localhost:8080/search?from=WAW&to=AGP&date=2026-09-12"
```

## Configuration

Edit `app/src/main/resources/application.yml` to change database settings.

## Notes

- Requires Java 21 with preview features enabled
- API endpoints may change; scrapers may need updates
- Respect rate limits when scraping
