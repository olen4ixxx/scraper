package org.example.flightsearch.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.flightsearch.mobile.data.ApiClient
import org.example.flightsearch.mobile.data.Destination
import org.example.flightsearch.mobile.data.SearchForm
import org.example.flightsearch.mobile.data.SearchResult
import org.example.flightsearch.mobile.data.Settings
import java.time.LocalDate

enum class Screen { SEARCH, RESULTS, SETTINGS }

class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = Settings(app)
    private val api = ApiClient { settings.baseUrl }

    private val _screen = MutableStateFlow(if (settings.baseUrl.isBlank()) Screen.SETTINGS else Screen.SEARCH)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _baseUrl = MutableStateFlow(settings.baseUrl)
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _form = MutableStateFlow(SearchForm(departure = LocalDate.now().toString()))
    val form: StateFlow<SearchForm> = _form.asStateFlow()

    private val _destinations = MutableStateFlow<List<Destination>>(emptyList())
    val destinations: StateFlow<List<Destination>> = _destinations.asStateFlow()

    private val _results = MutableStateFlow<List<SearchResult>>(emptyList())
    val results: StateFlow<List<SearchResult>> = _results.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        if (settings.baseUrl.isNotBlank()) loadDestinations()
    }

    fun updateForm(transform: (SearchForm) -> SearchForm) {
        _form.value = transform(_form.value)
    }

    fun goTo(screen: Screen) {
        _screen.value = screen
    }

    fun dismissError() {
        _error.value = null
    }

    fun saveBaseUrl(url: String) {
        settings.baseUrl = url
        _baseUrl.value = settings.baseUrl
        _error.value = null
        loadDestinations()
        _screen.value = Screen.SEARCH
    }

    fun loadDestinations() {
        if (settings.baseUrl.isBlank()) return
        viewModelScope.launch {
            _loading.value = true
            try {
                _destinations.value = api.destinations()
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Could not reach the server: ${e.message}"
            } finally {
                _loading.value = false
            }
        }
    }

    fun search() {
        val current = _form.value
        if (current.to.isEmpty()) {
            _error.value = "Pick at least one destination"
            return
        }
        viewModelScope.launch {
            _loading.value = true
            _error.value = null
            _screen.value = Screen.RESULTS
            try {
                _results.value = api.search(current)
            } catch (e: Exception) {
                _results.value = emptyList()
                _error.value = e.message ?: e.toString()
            } finally {
                _loading.value = false
            }
        }
    }
}
