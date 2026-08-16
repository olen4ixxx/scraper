package org.example.flightsearch.common.model;

/**
 * Every airline that can appear in the database, which is not the same as every airline this
 * application collects itself - ticket-finder writes into the same schema, so a value has to
 * exist here before its rows can be read back, whichever tool wrote them.
 */
public enum Airline {
    WIZZAIR,
    RYANAIR,
    VUELING,
    TRANSAVIA,
    EASYJET,
    VOLOTEA
}
