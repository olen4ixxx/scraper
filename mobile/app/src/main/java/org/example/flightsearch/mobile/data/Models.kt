package org.example.flightsearch.mobile.data

import kotlinx.serialization.Serializable

@Serializable
data class Destination(
    val iata: String = "",
    val name: String = "",
    val city: String = "",
    val country: String = "",
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
data class Segment(
    val flightId: Long? = null,
    val airline: String = "",
    val fromAirport: String = "",
    val fromCity: String? = null,
    val toAirport: String = "",
    val toCity: String? = null,
    val departure: String = "",
    val arrival: String = "",
    val price: Double = 0.0,
    val currency: String = "",
    val duration: String? = null,
)

@Serializable
data class SearchResult(
    val totalPrice: Double = 0.0,
    val currency: String = "",
    val airlines: List<String> = emptyList(),
    val departure: String = "",
    val arrival: String = "",
    val duration: String? = null,
    val numberOfStops: Int = 0,
    val segments: List<Segment> = emptyList(),
    val returnDeparture: String? = null,
    val returnArrival: String? = null,
    val returnDuration: String? = null,
    val returnNumberOfStops: Int = 0,
    val returnSegments: List<Segment> = emptyList(),
) {
    val isRoundTrip: Boolean get() = returnSegments.isNotEmpty()
}

enum class SortBy(val label: String) {
    CHEAPEST("Cheapest"),
    SHORTEST("Shortest"),
    EARLIEST_DEPARTURE("Earliest departure"),
    LATEST_DEPARTURE("Latest departure"),
    FEWEST_STOPS("Fewest stops"),
}

enum class ToKind { ANYWHERE, COUNTRY, CITY, AIRPORT }

data class ToSelection(val label: String, val value: String, val kind: ToKind)

data class OriginOption(val value: String, val label: String)

val ORIGINS = listOf(
    OriginOption("POLAND", "Poland (all airports)"),
    OriginOption("WARSAW", "Warsaw (WAW + WMI)"),
    OriginOption("WAW", "Warsaw Chopin (WAW)"),
    OriginOption("WMI", "Warsaw Modlin (WMI)"),
    OriginOption("KRK", "Krakow (KRK)"),
    OriginOption("GDN", "Gdansk (GDN)"),
    OriginOption("WRO", "Wroclaw (WRO)"),
    OriginOption("POZ", "Poznan (POZ)"),
    OriginOption("KTW", "Katowice (KTW)"),
    OriginOption("RZE", "Rzeszow (RZE)"),
    OriginOption("LUZ", "Lublin (LUZ)"),
    OriginOption("SZZ", "Szczecin (SZZ)"),
    OriginOption("BZG", "Bydgoszcz (BZG)"),
)

/**
 * Mirrors the web form one-for-one so the two frontends can't drift apart: every field here
 * maps to a single /api/search query parameter, except the connection window, which the form
 * edits as hours/minutes/days and collapses into minutes at request time.
 */
data class SearchForm(
    val from: String = "POLAND",
    val to: List<ToSelection> = listOf(ToSelection("Anywhere", "ANYWHERE", ToKind.ANYWHERE)),
    // Explicit rather than inferred from returnDate: leaving a date field blank is a poor way
    // to say "one way", and the round-trip-only options need something to key off.
    val roundTrip: Boolean = false,
    val departure: String = "",
    val departureRangeEnd: String? = null,
    val returnDate: String? = null,
    val returnRangeEnd: String? = null,
    val maxStops: Int = 1,
    val minConnHours: Int = 0,
    val minConnMinutes: Int = 30,
    val maxConnHours: Int = 24,
    val maxConnMinutes: Int = 0,
    val maxConnDays: Int = 0,
    val allowOvernightConnection: Boolean = false,
    val allowGroundTransfer: Boolean = false,
    val groundTransferRadiusKm: Int = 100,
    val stayMinDays: Int? = null,
    val stayMaxDays: Int? = null,
    val allowReturnToDifferentAirport: Boolean = false,
    val allowReturnFromDifferentAirport: Boolean = false,
    val ryanair: Boolean = true,
    val wizzair: Boolean = true,
    val sortBy: SortBy = SortBy.CHEAPEST,
) {
    val minConnectionMinutes: Int get() = minConnHours * 60 + minConnMinutes

    // A days value of 1+ overrides the hours/minutes upper bound, matching the web form's
    // "0 = off, overrides 'to' above when >= 1" hint.
    val maxConnectionMinutes: Int
        get() = if (maxConnDays >= 1) maxConnDays * 1440 else maxConnHours * 60 + maxConnMinutes

    val airlines: List<String>
        get() = buildList {
            if (ryanair) add("RYANAIR")
            if (wizzair) add("WIZZAIR")
        }
}
