package xyz.attacktive.weatherd.domain.model

import kotlin.math.roundToInt

/** The unit the wallpaper's temperature label is displayed in; the weather API always reports Celsius. */
enum class TemperatureUnit {
	CELSIUS,
	FAHRENHEIT;

	/** Formats an API-reported Celsius reading as a rounded bare degree in this unit, e.g. "23°" — the user chose the unit, so the C/F suffix would be noise. */
	fun format(temperatureCelsius: Double): String {
		val degrees = when (this) {
			CELSIUS -> temperatureCelsius
			FAHRENHEIT -> temperatureCelsius * 9.0 / 5.0 + 32.0
		}

		return "${degrees.roundToInt()}°"
	}

	companion object {
		/** The unit stored under [name], or [CELSIUS] when the value is absent or unrecognised (e.g. read by an older build after a downgrade). */
		fun fromName(name: String?) = entries.firstOrNull { it.name == name } ?: CELSIUS
	}
}
