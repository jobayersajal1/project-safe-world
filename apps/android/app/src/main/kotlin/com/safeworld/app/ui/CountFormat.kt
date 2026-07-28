package com.safeworld.app.ui

import android.icu.text.CompactDecimalFormat
import java.text.NumberFormat
import java.util.Locale

/**
 * Number formatting for the home screen's headline.
 *
 * Both go through the platform formatters rather than string concatenation so
 * the digits and separators follow the UI language — Bangla renders its own
 * numerals, Spanish groups with dots, Arabic reads right to left. A hand-rolled
 * `"${n / 1000}K"` would be wrong in three of the four languages this app ships.
 */
object CountFormat {

    /**
     * "85 thousand" — the headline figure, rounded to something readable at a
     * glance.
     *
     * LONG rather than SHORT, even though SHORT is what gives English "85K",
     * because this sits inside a sentence and CLDR's short forms are meant for
     * constrained space — axis labels, chips. In Spanish and Arabic the two are
     * identical anyway ("85 mil", "٨٥ ألف"); in Bengali SHORT truncates
     * হাজার to "হা", which reads like a cut-off word mid-sentence.
     */
    fun compact(value: Int, locale: Locale = Locale.getDefault()): String =
        CompactDecimalFormat
            .getInstance(locale, CompactDecimalFormat.CompactStyle.LONG)
            .format(value)

    /** "64,238" — the exact number, for the line that backs up the claim. */
    fun grouped(value: Int, locale: Locale = Locale.getDefault()): String =
        NumberFormat.getIntegerInstance(locale).format(value)
}
