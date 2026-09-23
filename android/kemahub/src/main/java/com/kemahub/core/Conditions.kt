package com.kemahub.core

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
