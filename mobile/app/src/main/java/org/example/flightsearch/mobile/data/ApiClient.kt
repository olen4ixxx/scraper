package org.example.flightsearch.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiClient(private val baseUrlProvider: () -> String) {

    private val http = OkHttpClient.Builder()
        // Searches over a wide date range can take a while on the backend, and the phone is
        // often on a slow link - the default 10s read timeout trips long before the server
        // is actually done.
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun destinations(): List<Destination> = withContext(Dispatchers.IO) {
        val url = base().newBuilder().addPathSegments("api/destinations").build()
        json.decodeFromString(get(url))
    }

    suspend fun search(form: SearchForm): List<SearchResult> = withContext(Dispatchers.IO) {
        val url = base().newBuilder().apply {
            addPathSegments("api/search")
            addQueryParameter("from", form.from)
            addQueryParameter("to", form.to.joinToString(",") { it.value })
            addQueryParameter("departure", form.departure)
            form.departureRangeEnd?.let { addQueryParameter("departureRangeEnd", it) }
            // Round-trip-only parameters stay off the wire entirely for a one-way search, so a
            // return date left over from an earlier search can't leak into it.
            if (form.roundTrip) {
                form.returnDate?.let { addQueryParameter("returnDate", it) }
                form.returnRangeEnd?.let { addQueryParameter("returnRangeEnd", it) }
            }
            addQueryParameter("maxStops", form.maxStops.toString())
            if (form.airlines.isNotEmpty()) {
                addQueryParameter("airlines", form.airlines.joinToString(","))
            }
            addQueryParameter("sortBy", form.sortBy.name)
            addQueryParameter("minConnectionMinutes", form.minConnectionMinutes.toString())
            addQueryParameter("maxConnectionMinutes", form.maxConnectionMinutes.toString())
            if (form.roundTrip) {
                form.stayMinDays?.let { addQueryParameter("stayMinDays", it.toString()) }
                form.stayMaxDays?.let { addQueryParameter("stayMaxDays", it.toString()) }
                addQueryParameter("allowReturnToDifferentAirport", form.allowReturnToDifferentAirport.toString())
                addQueryParameter("allowReturnFromDifferentAirport", form.allowReturnFromDifferentAirport.toString())
            }
            addQueryParameter("allowOvernightConnection", form.allowOvernightConnection.toString())
            addQueryParameter("allowGroundTransfer", form.allowGroundTransfer.toString())
            if (form.allowGroundTransfer) {
                addQueryParameter("groundTransferRadiusKm", form.groundTransferRadiusKm.toString())
            }
        }.build()
        json.decodeFromString(get(url))
    }

    private fun base(): HttpUrl {
        val raw = baseUrlProvider().trim().trimEnd('/')
        val withScheme = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        return withScheme.toHttpUrlOrNull()
            ?: throw IOException("Bad server address: \"$raw\". Expected something like 192.168.1.20:8080")
    }

    private fun get(url: HttpUrl): String {
        val response = http.newCall(Request.Builder().url(url).build()).execute()
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IOException("Server returned ${it.code}${if (body.isBlank()) "" else ": ${body.take(300)}"}")
            }
            return body
        }
    }
}
