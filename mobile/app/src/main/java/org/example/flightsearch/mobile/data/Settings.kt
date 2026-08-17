package org.example.flightsearch.mobile.data

import android.content.Context

class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("flight-search", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim()).apply()

    private companion object {
        const val KEY_BASE_URL = "baseUrl"
    }
}
