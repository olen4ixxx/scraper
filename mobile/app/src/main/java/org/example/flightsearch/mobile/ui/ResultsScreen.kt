package org.example.flightsearch.mobile.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FlightLand
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.flightsearch.mobile.data.SearchResult
import org.example.flightsearch.mobile.data.Segment
import org.example.flightsearch.mobile.data.SortBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultsScreen(
    results: List<SearchResult>,
    subtitle: String,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
) {
    // Re-sorting happens on the results already in hand rather than by re-running the search -
    // the whole list is here, and a round trip to the backend to reorder it would be wasteful.
    var sortBy by remember { mutableStateOf(SortBy.CHEAPEST) }
    val sorted = remember(results, sortBy) { sortResults(results, sortBy) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(Modifier.background(headerBrush)) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (loading) "Searching…" else "${results.size} results",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (subtitle.isNotBlank()) {
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to search")
                        }
                    },
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!loading && error == null && results.isNotEmpty()) {
                Surface(tonalElevation = 2.dp) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SortBy.entries.forEach { option ->
                            FilterChip(
                                selected = option == sortBy,
                                onClick = { sortBy = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    error != null -> Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    )

                    results.isEmpty() -> Text(
                        "Nothing found for these filters. Try widening the dates, allowing a " +
                            "stop, or picking more destinations.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center).padding(32.dp),
                    )

                    else -> LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(sorted) { ResultCard(it) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: SearchResult) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .animateContentSize(),
    ) {
        // A thin accent stripe along the top edge ties each card back to the app's blue without
        // colouring the whole surface, which would fight the text.
        Box(Modifier.fillMaxWidth().height(3.dp).background(accentBrush))
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatPrice(result.totalPrice, result.currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    result.airlines.distinct().forEach { airline ->
                        AirlineBadge(airline)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Leg(
                icon = true,
                departure = result.departure,
                arrival = result.arrival,
                duration = result.duration,
                stops = result.numberOfStops,
                segments = result.segments,
                expanded = expanded,
            )

            if (result.isRoundTrip) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Leg(
                    icon = false,
                    departure = result.returnDeparture,
                    arrival = result.returnArrival,
                    duration = result.returnDuration,
                    stops = result.returnNumberOfStops,
                    segments = result.returnSegments,
                    expanded = expanded,
                )
            }

            if (!expanded && (result.segments.size > 1 || result.isRoundTrip)) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Tap for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A read-only label, not a control. A disabled chip would be the obvious thing to reach for,
 * but Material greys disabled chips out, which reads as "unavailable" rather than "this is the
 * airline".
 */
@Composable
private fun AirlineBadge(airline: String) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(8.dp),
    ) {
        Text(
            airline.lowercase().replaceFirstChar(Char::uppercase),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

/** One direction of travel, drawn as departure — route line — arrival. */
@Composable
private fun Leg(
    icon: Boolean,
    departure: String?,
    arrival: String?,
    duration: String?,
    stops: Int,
    segments: List<Segment>,
    expanded: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = if (icon) Icons.Filled.FlightTakeoff else Icons.Filled.FlightLand,
            contentDescription = if (icon) "Outbound" else "Return",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            formatDay(departure),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(Modifier.height(6.dp))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.Start) {
            Text(formatTime(departure), style = MaterialTheme.typography.titleMedium)
            Text(
                segments.firstOrNull()?.fromAirport.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        ) {
            Text(
                formatDuration(duration),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RouteLine(stops)
            Text(
                stopsLabel(stops),
                style = MaterialTheme.typography.labelSmall,
                color = if (stops == 0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(formatTime(arrival), style = MaterialTheme.typography.titleMedium)
            Text(
                segments.lastOrNull()?.toAirport.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (expanded) {
        Spacer(Modifier.height(10.dp))
        segments.forEach { segment ->
            Column(Modifier.padding(start = 4.dp, top = 6.dp)) {
                Text(
                    "${segment.fromCity ?: segment.fromAirport} (${segment.fromAirport})  →  " +
                        "${segment.toCity ?: segment.toAirport} (${segment.toAirport})",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${formatDayTime(segment.departure)} – ${formatDayTime(segment.arrival)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${segment.airline.lowercase().replaceFirstChar(Char::uppercase)} · " +
                        formatPrice(segment.price, segment.currency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Dot — line — dot, with an extra dot per stop, so stop count is readable at a glance. */
@Composable
private fun RouteLine(stops: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Dot()
        repeat(stops.coerceAtMost(3)) {
            Bar(Modifier.weight(1f))
            Dot()
        }
        Bar(Modifier.weight(1f))
        Dot()
    }
}

@Composable
private fun Dot() {
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
    )
}

@Composable
private fun Bar(modifier: Modifier) {
    Box(
        modifier
            .height(2.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

private fun sortResults(results: List<SearchResult>, sortBy: SortBy): List<SearchResult> =
    when (sortBy) {
        SortBy.CHEAPEST -> results.sortedBy { it.totalPrice }
        SortBy.SHORTEST -> results.sortedBy {
            durationMinutes(it.duration) + if (it.isRoundTrip) durationMinutes(it.returnDuration) else 0
        }
        SortBy.EARLIEST_DEPARTURE -> results.sortedBy { parseDateTime(it.departure) }
        SortBy.LATEST_DEPARTURE -> results.sortedByDescending { parseDateTime(it.departure) }
        SortBy.FEWEST_STOPS -> results.sortedBy { it.numberOfStops + it.returnNumberOfStops }
    }
