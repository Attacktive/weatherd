package xyz.attacktive.weatherd.domain.model

/** Current-conditions snapshot for one location, distilled from the Open-Meteo response. */
data class WeatherObservation(val weatherCode: Int, val isDay: Boolean, val temperatureCelsius: Double, val precipitationMillimeters: Double, val windSpeedKilometersPerHour: Double, val cloudCoverPercent: Int)
