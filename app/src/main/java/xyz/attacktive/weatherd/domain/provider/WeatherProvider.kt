package xyz.attacktive.weatherd.domain.provider

import xyz.attacktive.weatherd.domain.model.WeatherSnapshot

interface WeatherProvider {
	suspend fun current(latitude: Double, longitude: Double): WeatherSnapshot
}
