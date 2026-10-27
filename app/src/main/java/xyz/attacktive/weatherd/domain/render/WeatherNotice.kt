package xyz.attacktive.weatherd.domain.render

/** Immutable provenance for retained weather; a missing success time means no forecast is available. */
data class WeatherNotice(val refreshedAtEpochSeconds: Long?, val locationLabel: String?) {
	companion object {
		val UNAVAILABLE = WeatherNotice(null, null)
	}
}
