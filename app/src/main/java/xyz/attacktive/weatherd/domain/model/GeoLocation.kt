package xyz.attacktive.weatherd.domain.model

/** A resolved geographic point to fetch weather for, optionally with a human-readable label. */
data class GeoLocation(val latitude: Double, val longitude: Double, val label: String? = null)
