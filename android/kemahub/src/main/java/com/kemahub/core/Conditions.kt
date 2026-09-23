package com.kemahub.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Active on the [days] of the week (bit 0 = Monday .. bit 6 = Sunday) from [start] until [end]
 * (minutes of the day). [start] == [end] means the whole day; [start] > [end] runs past
 * midnight, and the part after midnight belongs to the day it started on.
 */
data class TimeRule(val days: Int = WEEKDAYS, val start: Int = 9 * 60, val end: Int = 18 * 60) {
    /** [day] is ISO (1 = Monday .. 7 = Sunday), [minute] the minute of the day. */
    fun matches(day: Int, minute: Int): Boolean = when {
        start == end -> on(day)
        start < end -> on(day) && minute >= start && minute < end
        else -> (on(day) && minute >= start) || (on(if (day == 1) 7 else day - 1) && minute < end)
    }

    fun on(day: Int) = days and (1 shl (day - 1)) != 0

    fun toggle(day: Int) = copy(days = days xor (1 shl (day - 1)))

    val isValid get() = days in 1..ALL && start in 0 until DAY && end in 0 until DAY

    companion object {
        const val WEEKDAYS = 0b0011111
        const val WEEKEND = 0b1100000
        const val ALL = 0b1111111
        const val DAY = 24 * 60
    }
}

/** A position as reported by the phone; [accuracy] in meters. */
data class GeoPoint(val lat: Double, val lon: Double, val accuracy: Float = 0f)

/** Active within [radius] meters of a place. [label] is optional ("Office"). */
data class PlaceRule(val lat: Double, val lon: Double, val radius: Int = 200, val label: String = "") {
    /** Inside, allowing for part of the fix's inaccuracy (up to the radius itself). */
    fun contains(p: GeoPoint): Boolean = distance(lat, lon, p.lat, p.lon) <= radius + min(p.accuracy.toDouble(), radius.toDouble())

    val isValid get() = lat in -90.0..90.0 && lon in -180.0..180.0 && radius > 0

    companion object {
        val RADII = listOf(100, 200, 500, 1000, 2000)

        /** Great-circle distance in meters. */
        fun distance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
