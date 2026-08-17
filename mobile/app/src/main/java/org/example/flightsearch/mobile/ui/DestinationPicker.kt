package org.example.flightsearch.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import org.example.flightsearch.mobile.data.Destination
import org.example.flightsearch.mobile.data.ToKind
import org.example.flightsearch.mobile.data.ToSelection

private sealed interface Suggestion {
    val label: String
    val selection: ToSelection

    data class Anywhere(override val label: String, override val selection: ToSelection) : Suggestion
    data class Country(override val label: String, override val selection: ToSelection) : Suggestion
    data class City(override val label: String, override val selection: ToSelection) : Suggestion
    data class Airport(override val label: String, override val selection: ToSelection) : Suggestion
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DestinationPicker(
    destinations: List<Destination>,
    selected: List<ToSelection>,
    onChange: (List<ToSelection>) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(false) }

    fun add(selection: ToSelection) {
        val next = if (selection.kind == ToKind.ANYWHERE) {
            listOf(selection)
        } else {
            selected.filter { it.kind != ToKind.ANYWHERE && it.value != selection.value } + selection
        }
        query = ""
        open = false
        onChange(next)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel("To")
        if (selected.isNotEmpty()) {
            TextButton(onClick = { onChange(emptyList()) }) { Text("Clear all") }
        }
    }

    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        selected.forEach { selection ->
            AssistChip(
                onClick = { onChange(selected - selection) },
                label = { Text(selection.label) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove") },
            )
        }
    }

    OutlinedTextField(
        value = query,
        onValueChange = {
            query = it
            open = true
        },
        label = { Text("Add city, airport code or country") },
        singleLine = true,
        // Focusing the field opens the full list, same as the web form does - otherwise
        // tapping it looks broken until you happen to type a character.
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .onFocusChanged { if (it.isFocused) open = true },
    )

    if (open) {
        val suggestions = remember(destinations, query) { buildSuggestions(destinations, query) }
        if (destinations.isEmpty()) {
            Text(
                "Destination list hasn't loaded from the server yet, so only \"Anywhere\" is " +
                    "available. Check the server address, then use Retry above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(8.dp),
            )
        }
        if (suggestions.isEmpty()) {
            Text(
                "No matches",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                items(suggestions) { suggestion ->
                    SuggestionRow(suggestion) { add(suggestion.selection) }
                }
            }
            TextButton(onClick = { open = false }) { Text("Close list") }
        }
    }
}

@Composable
private fun SuggestionRow(suggestion: Suggestion, onClick: () -> Unit) {
    val indent = when (suggestion) {
        is Suggestion.Anywhere -> 12.dp
        is Suggestion.Country -> 12.dp
        is Suggestion.City -> 28.dp
        is Suggestion.Airport -> 40.dp
    }
    val weight = when (suggestion) {
        is Suggestion.Country -> FontWeight.Bold
        is Suggestion.Anywhere, is Suggestion.City -> FontWeight.SemiBold
        is Suggestion.Airport -> FontWeight.Normal
    }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(
            text = suggestion.label,
            fontWeight = weight,
            style = MaterialTheme.typography.bodyMedium,
            color = if (suggestion is Suggestion.Country) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = indent, end = 12.dp, top = 8.dp, bottom = 8.dp),
        )
    }
}

/**
 * Same country > city > airport grouping the web form builds, so a destination typed on the
 * phone resolves to exactly the token the backend would have received from the browser.
 */
private fun buildSuggestions(destinations: List<Destination>, rawQuery: String): List<Suggestion> {
    val query = rawQuery.trim().lowercase()
    val out = mutableListOf<Suggestion>()

    if (query.isEmpty() || "anywhere".contains(query)) {
        out += Suggestion.Anywhere("Anywhere", ToSelection("Anywhere", "ANYWHERE", ToKind.ANYWHERE))
    }

    destinations.groupBy { it.country }.toSortedMap().forEach { (country, airports) ->
        val countryMatches = country.lowercase().contains(query)
        val anyAirportMatches = airports.any {
            it.city.lowercase().contains(query) ||
                it.iata.lowercase().contains(query) ||
                it.name.lowercase().contains(query)
        }
        if (query.isNotEmpty() && !countryMatches && !anyAirportMatches) return@forEach

        var countryHeaderAdded = false
        airports.groupBy { it.city }.toSortedMap().forEach { (city, cityAirports) ->
            val cityMatches = countryMatches || city.lowercase().contains(query)
            val shown = cityAirports.filter {
                cityMatches || it.iata.lowercase().contains(query) || it.name.lowercase().contains(query)
            }
            if (shown.isEmpty()) return@forEach

            if (!countryHeaderAdded) {
                out += Suggestion.Country(
                    "🌍 All of $country (${airports.size})",
                    ToSelection("All of $country", "COUNTRY:$country", ToKind.COUNTRY),
                )
                countryHeaderAdded = true
            }
            if (cityAirports.size > 1) {
                out += Suggestion.City(
                    "$city (All ${cityAirports.size})",
                    ToSelection("$city (All)", "CITY:$city", ToKind.CITY),
                )
            }
            shown.forEach { airport ->
                out += Suggestion.Airport(
                    "${airport.city} (${airport.iata})",
                    ToSelection("${airport.city} (${airport.iata})", airport.iata, ToKind.AIRPORT),
                )
            }
        }
    }
    return out
}
