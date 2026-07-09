package xyz.attacktive.weatherd.domain.model

/** A named location resolved from a city search, ready to display and to persist as a manual override. */
data class GeoPlace(val name: String, val latitude: Double, val longitude: Double, val region: String? = null, val country: String? = null) {
	/** A human label like "Tokyo, Japan" or "Springfield, Illinois, United States"; the region is dropped when it just echoes the city. */
	val label get() = listOfNotNull(name, region?.takeIf { it != name }, country).joinToString(", ")
}
