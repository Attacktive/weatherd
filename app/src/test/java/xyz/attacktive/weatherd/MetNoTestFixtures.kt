package xyz.attacktive.weatherd

import xyz.attacktive.weatherd.data.api.dto.MetNoDataDto
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoInstantDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPeriodDetailsDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPeriodDto
import xyz.attacktive.weatherd.data.api.dto.MetNoPropertiesDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSolarPositionDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSummaryDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunEventDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunPropertiesDto
import xyz.attacktive.weatherd.data.api.dto.MetNoSunriseResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoTimeSeriesDto

internal fun metNoForecastResponse(symbolCode: String, precipitationAmount: Double? = 3.2) = MetNoForecastResponseDto(
	properties = MetNoPropertiesDto(
		timeseries = listOf(
			MetNoTimeSeriesDto(
				time = "2025-09-14T03:00:00Z",
				data = MetNoDataDto(
					instant = MetNoInstantDto(
						details = MetNoInstantDetailsDto(
							airTemperature = 18.4,
							cloudAreaFraction = 77.8,
							windSpeed = 5.0
						)
					),
					nextOneHour = MetNoPeriodDto(
						summary = MetNoSummaryDto(symbolCode),
						details = MetNoPeriodDetailsDto(precipitationAmount)
					)
				)
			)
		)
	)
)

internal fun metNoSunResponse(
	sunrise: String? = "2025-09-14T00:00:00Z",
	sunset: String? = "2025-09-14T12:00:00Z",
	solarNoonVisible: Boolean = true
) = MetNoSunriseResponseDto(
	properties = MetNoSunPropertiesDto(
		sunrise = sunrise?.let(::MetNoSunEventDto),
		sunset = sunset?.let(::MetNoSunEventDto),
		solarnoon = MetNoSolarPositionDto(solarNoonVisible),
		solarMidnight = MetNoSolarPositionDto(!solarNoonVisible)
	)
)
