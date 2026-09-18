package xyz.attacktive.weatherd.data.api.dto

import java.time.OffsetDateTime
import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoSunriseResponseDto(val properties: MetNoSunPropertiesDto = MetNoSunPropertiesDto())

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoSunPropertiesDto(
	val sunrise: MetNoSunEventDto? = null,
	val sunset: MetNoSunEventDto? = null,
	val solarnoon: MetNoSolarPositionDto? = null,
	@SerialName("solarmidnight") val solarMidnight: MetNoSolarPositionDto? = null
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoSunEventDto(val time: String? = null)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MetNoSolarPositionDto(val visible: Boolean? = null)

internal fun MetNoSunriseResponseDto.sunriseEpochSeconds() = properties.sunrise?.time?.let { OffsetDateTime.parse(it).toEpochSecond() }

internal fun MetNoSunriseResponseDto.sunsetEpochSeconds() = properties.sunset?.time?.let { OffsetDateTime.parse(it).toEpochSecond() }

internal fun MetNoSunriseResponseDto.isDayAt(epochSeconds: Long): Boolean {
	val sunrise = sunriseEpochSeconds()
	val sunset = sunsetEpochSeconds()

	return when {
		sunrise != null && sunset != null -> epochSeconds in sunrise..sunset
		sunrise != null -> epochSeconds >= sunrise
		sunset != null -> epochSeconds <= sunset
		else -> properties.solarnoon?.visible ?: error("MET Norway sunrise response contains no usable solar visibility")
	}
}
