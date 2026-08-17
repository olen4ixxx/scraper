package org.example.flightsearch.mobile.ui

import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Locale.ENGLISH, not ROOT: root has no month or weekday names and renders September as "M09".
private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val DAY_TIME = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

fun parseDateTime(value: String?): LocalDateTime? =
    value?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

fun formatTime(value: String?): String = parseDateTime(value)?.format(TIME) ?: "—"

fun formatDay(value: String?): String = parseDateTime(value)?.format(DAY) ?: "—"

fun formatDayTime(value: String?): String = parseDateTime(value)?.format(DAY_TIME) ?: "—"

/** Backend sends java.time.Duration, which Jackson writes as an ISO-8601 string ("PT4H35M"). */
fun formatDuration(value: String?): String {
    val duration = value?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Duration.parse(it) }.getOrNull() }
        ?: return "—"
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

fun formatPrice(amount: Double, currency: String): String =
    String.format(Locale.ROOT, "%.2f %s", amount, currency)

private val FORM_DAY = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH)
private val FORM_DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/**
 * Date as shown on the search form: "Thu, 13 Aug" reads far better than the ISO string the
 * form actually submits, but a date outside the current year has to carry the year or it is
 * plainly ambiguous.
 */
fun formatFormDate(isoDate: String?): String {
    val date = isoDate?.takeIf { it.isNotBlank() }
        ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
        ?: return ""
    return if (date.year == java.time.LocalDate.now().year) {
        date.format(FORM_DAY)
    } else {
        date.format(FORM_DAY_YEAR)
    }
}

/** Minutes as a compact "2h 30m" / "45m", for the connection-window summary line. */
fun formatMinutes(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        else -> "${minutes}m"
    }
}

/** Sort key in minutes; unparseable or missing durations sort last rather than crashing. */
fun durationMinutes(value: String?): Long =
    value?.takeIf { it.isNotBlank() }
        ?.let { runCatching { Duration.parse(it).toMinutes() }.getOrNull() }
        ?: Long.MAX_VALUE

fun stopsLabel(stops: Int): String = when (stops) {
    0 -> "Direct"
    1 -> "1 stop"
    else -> "$stops stops"
}
