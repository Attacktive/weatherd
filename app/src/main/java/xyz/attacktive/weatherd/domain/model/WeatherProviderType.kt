package xyz.attacktive.weatherd.domain.model

enum class WeatherProviderType {
	OPEN_METEO,
	MET_NORWAY,
	DWD_ICON_GLOBAL,
	DWD_ICON_EU,
	DWD_ICON_D2,
	ITALIA_METEO,
	METEOSWISS_ICON_CH1,
	METEOSWISS_ICON_CH2;

	companion object {
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: OPEN_METEO
	}
}
