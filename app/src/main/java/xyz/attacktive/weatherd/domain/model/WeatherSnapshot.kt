package xyz.attacktive.weatherd.domain.model

/** A current-conditions observation plus the sun times used to tint the scene for time of day. */
data class WeatherSnapshot(val observation: WeatherObservation, val observedAtEpochSeconds: Long, val sunriseEpochSeconds: Long?, val sunsetEpochSeconds: Long?)
