package xyz.attacktive.weatherd.domain.model

enum class WeatherProviderType {
	OPEN_METEO,
	MET_NORWAY;

	companion object {
		fun fromName(name: String?): WeatherProviderType = entries.firstOrNull { it.name == name } ?: OPEN_METEO
	}
}
