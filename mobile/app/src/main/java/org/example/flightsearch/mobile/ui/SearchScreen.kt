package org.example.flightsearch.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.flightsearch.mobile.data.Destination
import org.example.flightsearch.mobile.data.ORIGINS
import org.example.flightsearch.mobile.data.SearchForm
import org.example.flightsearch.mobile.data.ToSelection

private val MAX_STOPS = listOf(0, 1, 2)
private val TRIP_TYPES = listOf(false, true)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    form: SearchForm,
    destinations: List<Destination>,
    loading: Boolean,
    error: String?,
    onFormChange: ((SearchForm) -> SearchForm) -> Unit,
    onOpenSettings: () -> Unit,
    onRetryDestinations: () -> Unit,
    onSearch: () -> Unit,
) {
    // Which advanced cards are open is view state, not search state - it should not survive
    // into the request or the results screen.
    var connectionOpen by remember { mutableStateOf(false) }
    var flexibilityOpen by remember { mutableStateOf(false) }
    var airlinesOpen by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Box(Modifier.background(headerBrush)) {
                TopAppBar(
                    title = {
                        Text(
                            "✈  Flight Search",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    actions = {
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Filled.Settings, contentDescription = "Server settings")
                        }
                    },
                )
            }
        },
        // The search button used to sit at the very bottom of a long scroll, so finding it meant
        // scrolling past every option. Pinned, it is always one tap away.
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                // The gradient lives on a Box behind a transparent Button so the button keeps
                // its ripple, elevation and disabled handling instead of being hand-rolled.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .clip(RoundedCornerShape(26.dp))
                        .background(if (loading) SolidColor(MaterialTheme.colorScheme.outlineVariant) else accentBrush),
                ) {
                    Button(
                        onClick = onSearch,
                        enabled = !loading,
                        shape = RoundedCornerShape(26.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color.White,
                            disabledContainerColor = Color.Transparent,
                            disabledContentColor = Color.White,
                        ),
                        elevation = null,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(22.dp).width(22.dp),
                                strokeWidth = 2.dp,
                                color = Color.White,
                            )
                        } else {
                            Text(
                                "Search flights",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            ConnectionBanner(
                error = error,
                destinationsEmpty = destinations.isEmpty(),
                loading = loading,
                onRetry = onRetryDestinations,
                onOpenSettings = onOpenSettings,
            )

            Spacer(Modifier.height(12.dp))
            SegmentedChoice(
                options = TRIP_TYPES,
                selected = form.roundTrip,
                optionLabel = { if (it) "Round trip" else "One way" },
            ) { value -> onFormChange { it.copy(roundTrip = value) } }

            SectionLabel("From")
            Dropdown(
                label = "Departure airport",
                options = ORIGINS,
                selected = ORIGINS.firstOrNull { it.value == form.from } ?: ORIGINS.first(),
                optionLabel = { it.label },
                modifier = Modifier.fillMaxWidth(),
            ) { option -> onFormChange { it.copy(from = option.value) } }

            DestinationPicker(
                destinations = destinations,
                selected = form.to,
                onChange = { selection: List<ToSelection> -> onFormChange { it.copy(to = selection) } },
            )

            SectionLabel(if (form.roundTrip) "Outbound" else "When")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Departure", form.departure, modifier = Modifier.weight(1f)) { value ->
                    onFormChange { it.copy(departure = value ?: it.departure) }
                }
                DateField("…through", form.departureRangeEnd, modifier = Modifier.weight(1f), clearable = true) { value ->
                    onFormChange { it.copy(departureRangeEnd = value) }
                }
            }
            Text(
                "Set \"…through\" to search a range of dates instead of one day.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            if (form.roundTrip) {
                SectionLabel("Return")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("Return", form.returnDate, modifier = Modifier.weight(1f), clearable = true) { value ->
                        onFormChange { it.copy(returnDate = value) }
                    }
                    DateField("…through", form.returnRangeEnd, modifier = Modifier.weight(1f), clearable = true) { value ->
                        onFormChange { it.copy(returnRangeEnd = value) }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    NumberField("Min stay, days", form.stayMinDays, modifier = Modifier.weight(1f), placeholder = "any") { value ->
                        onFormChange { it.copy(stayMinDays = value) }
                    }
                    NumberField("Max stay, days", form.stayMaxDays, modifier = Modifier.weight(1f), placeholder = "any") { value ->
                        onFormChange { it.copy(stayMaxDays = value) }
                    }
                }
            }

            SectionLabel("Stops")
            SegmentedChoice(
                options = MAX_STOPS,
                selected = form.maxStops,
                optionLabel = { if (it == 0) "Direct" else stopsLabel(it) },
            ) { value -> onFormChange { it.copy(maxStops = value) } }

            if (form.maxStops > 0) {
                ExpandableCard(
                    title = "Connection time",
                    summary = connectionSummary(form),
                    expanded = connectionOpen,
                    onToggle = { connectionOpen = !connectionOpen },
                ) {
                    HourMinuteRow(
                        label = "From",
                        hours = form.minConnHours,
                        minutes = form.minConnMinutes,
                        onHours = { value -> onFormChange { it.copy(minConnHours = value) } },
                        onMinutes = { value -> onFormChange { it.copy(minConnMinutes = value) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    HourMinuteRow(
                        label = "to",
                        hours = form.maxConnHours,
                        minutes = form.maxConnMinutes,
                        enabled = form.maxConnDays < 1,
                        onHours = { value -> onFormChange { it.copy(maxConnHours = value) } },
                        onMinutes = { value -> onFormChange { it.copy(maxConnMinutes = value) } },
                    )
                    Spacer(Modifier.height(8.dp))
                    NumberField("Max days to wait", form.maxConnDays, modifier = Modifier.fillMaxWidth()) { value ->
                        onFormChange { it.copy(maxConnDays = value ?: 0) }
                    }
                    Text(
                        "0 = off. From 1 day up this replaces the upper bound above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    CheckRow(
                        checked = form.allowOvernightConnection,
                        label = "I don't mind waiting overnight at the airport",
                    ) { value -> onFormChange { it.copy(allowOvernightConnection = value) } }
                }
            }

            ExpandableCard(
                title = "Airport flexibility",
                summary = flexibilitySummary(form),
                expanded = flexibilityOpen,
                onToggle = { flexibilityOpen = !flexibilityOpen },
            ) {
                if (form.maxStops > 0) {
                    CheckRow(
                        checked = form.allowGroundTransfer,
                        label = "Change airports on the ground between flights",
                    ) { value -> onFormChange { it.copy(allowGroundTransfer = value) } }
                    if (form.allowGroundTransfer) {
                        Text(
                            "e.g. land at Milan Malpensa, fly onward from Bergamo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        NumberField("Within, km", form.groundTransferRadiusKm, modifier = Modifier.width(160.dp)) { value ->
                            onFormChange { it.copy(groundTransferRadiusKm = value ?: 0) }
                        }
                    }
                }
                if (form.roundTrip) {
                    CheckRow(
                        checked = form.allowReturnToDifferentAirport,
                        label = "Return to a different home airport",
                    ) { value -> onFormChange { it.copy(allowReturnToDifferentAirport = value) } }
                    CheckRow(
                        checked = form.allowReturnFromDifferentAirport,
                        label = "Fly home from a different destination airport",
                    ) { value -> onFormChange { it.copy(allowReturnFromDifferentAirport = value) } }
                }
                if (form.maxStops == 0 && !form.roundTrip) {
                    Text(
                        "Nothing to relax on a direct one-way search.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            ExpandableCard(
                title = "Airlines",
                summary = airlinesSummary(form),
                expanded = airlinesOpen,
                onToggle = { airlinesOpen = !airlinesOpen },
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = form.ryanair,
                        onClick = { onFormChange { it.copy(ryanair = !it.ryanair) } },
                        label = { Text("Ryanair") },
                    )
                    FilterChip(
                        selected = form.wizzair,
                        onClick = { onFormChange { it.copy(wizzair = !it.wizzair) } },
                        label = { Text("Wizz Air") },
                    )
                }
                if (form.airlines.isEmpty()) {
                    Text(
                        "With no airline selected the search covers all of them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ConnectionBanner(
    error: String?,
    destinationsEmpty: Boolean,
    loading: Boolean,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (error == null && !(destinationsEmpty && !loading)) return

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                error ?: "No destinations loaded — check the server address.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onOpenSettings) { Text("Change server") }
            }
        }
    }
}

private fun connectionSummary(form: SearchForm): String {
    val lower = formatMinutes(form.minConnectionMinutes)
    val upper = if (form.maxConnDays >= 1) {
        "${form.maxConnDays}d"
    } else {
        formatMinutes(form.maxConnectionMinutes)
    }
    val overnight = if (form.allowOvernightConnection) ", overnight ok" else ""
    return "$lower – $upper$overnight"
}

private fun flexibilitySummary(form: SearchForm): String {
    val parts = buildList {
        if (form.maxStops > 0 && form.allowGroundTransfer) add("ground transfer ${form.groundTransferRadiusKm} km")
        if (form.roundTrip && form.allowReturnToDifferentAirport) add("any home airport")
        if (form.roundTrip && form.allowReturnFromDifferentAirport) add("any return airport")
    }
    return if (parts.isEmpty()) "Exact airports only" else parts.joinToString(", ")
}

private fun airlinesSummary(form: SearchForm): String = when {
    form.ryanair && form.wizzair -> "Ryanair, Wizz Air"
    form.ryanair -> "Ryanair only"
    form.wizzair -> "Wizz Air only"
    else -> "All airlines"
}
