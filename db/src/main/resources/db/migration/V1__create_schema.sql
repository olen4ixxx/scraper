CREATE TABLE airport (
    id BIGSERIAL PRIMARY KEY,
    iata VARCHAR(3) NOT NULL UNIQUE,
    name VARCHAR(255),
    city VARCHAR(255) NOT NULL,
    country VARCHAR(255) NOT NULL,
    lat DOUBLE PRECISION,
    lon DOUBLE PRECISION
);

CREATE TABLE route (
    id BIGSERIAL PRIMARY KEY,
    airline VARCHAR(20) NOT NULL,
    from_airport VARCHAR(3) NOT NULL REFERENCES airport(iata),
    to_airport VARCHAR(3) NOT NULL REFERENCES airport(iata),
    active BOOLEAN DEFAULT TRUE,
    UNIQUE(airline, from_airport, to_airport)
);

CREATE TABLE flight (
    id BIGSERIAL PRIMARY KEY,
    route_id BIGINT NOT NULL REFERENCES route(id),
    flight_number VARCHAR(20) NOT NULL,
    departure TIMESTAMP WITH TIME ZONE NOT NULL,
    arrival TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE price_snapshot (
    id BIGSERIAL PRIMARY KEY,
    flight_id BIGINT NOT NULL REFERENCES flight(id),
    price DOUBLE PRECISION NOT NULL,
    currency VARCHAR(3) NOT NULL,
    collected_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_flight_departure ON flight(departure);
CREATE INDEX idx_flight_route ON flight(route_id);
CREATE INDEX idx_price_snapshot_flight ON price_snapshot(flight_id);
CREATE INDEX idx_price_snapshot_collected ON price_snapshot(collected_at);
