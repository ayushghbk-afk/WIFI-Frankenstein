package com.spectrefi.app.util

import android.content.Context
import com.spectrefi.app.R
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Free-space path loss estimate. Returns 0.0 for unknown frequencies instead of the
 * NaN/-Infinity that log10(0) produces.
 */
fun calculateDistanceMeters(
    frequency: Int,
    level: Int,
    correctionFactor: Double = 1.0
): Double {
    if (frequency <= 0) return 0.0
    val exp = (27.55 - (20 * log10(frequency.toDouble())) + abs(level)) / 20.0
    return 10.0.pow(exp) * correctionFactor
}

/** Localised "<distance> m" string; the unit comes from resources, not from the code. */
fun calculateDistanceString(
    context: Context,
    frequency: Int,
    level: Int,
    correctionFactor: Double = 1.0
): String {
    if (frequency <= 0) return context.getString(R.string.unknown)
    return context.getString(
        R.string.distance_meters,
        calculateDistanceMeters(frequency, level, correctionFactor)
    )
}
