package org.example.flightsearch.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import org.example.flightsearch.mobile.data.ORIGINS
import org.example.flightsearch.mobile.data.SearchForm
import org.example.flightsearch.mobile.ui.FlightSearchTheme
import org.example.flightsearch.mobile.ui.formatFormDate
import org.example.flightsearch.mobile.ui.ResultsScreen
import org.example.flightsearch.mobile.ui.SearchScreen
import org.example.flightsearch.mobile.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FlightSearchTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot(vm: SearchViewModel = viewModel()) {
    val screen by vm.screen.collectAsState()
    val form by vm.form.collectAsState()
    val destinations by vm.destinations.collectAsState()
    val results by vm.results.collectAsState()
    val loading by vm.loading.collectAsState()
    val error by vm.error.collectAsState()
    val baseUrl by vm.baseUrl.collectAsState()

    when (screen) {
        Screen.SETTINGS -> SettingsScreen(
            currentBaseUrl = baseUrl,
            canGoBack = baseUrl.isNotBlank(),
            onBack = { vm.goTo(Screen.SEARCH) },
            onSave = vm::saveBaseUrl,
        )

        Screen.SEARCH -> SearchScreen(
            form = form,
            destinations = destinations,
            loading = loading,
            error = error,
            onFormChange = vm::updateForm,
            onOpenSettings = { vm.goTo(Screen.SETTINGS) },
            onRetryDestinations = vm::loadDestinations,
            onSearch = vm::search,
        )

        Screen.RESULTS -> ResultsScreen(
            results = results,
            subtitle = searchSummary(form),
            loading = loading,
            error = error,
            onBack = {
                vm.dismissError()
                vm.goTo(Screen.SEARCH)
            },
        )
    }
}

/** "Poland → Rome, Barcelona · Thu, 13 Aug" - what the results on screen are answering. */
private fun searchSummary(form: SearchForm): String {
    val from = ORIGINS.firstOrNull { it.value == form.from }?.label ?: form.from
    val to = form.to.joinToString(", ") { it.label }.ifBlank { "Anywhere" }
    val dates = buildString {
        append(formatFormDate(form.departure))
        form.departureRangeEnd?.let { append(" – ${formatFormDate(it)}") }
    }
    return "${from.substringBefore(" (")} → $to · $dates"
}
