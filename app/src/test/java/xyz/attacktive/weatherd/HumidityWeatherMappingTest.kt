package xyz.attacktive.weatherd

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import xyz.attacktive.weatherd.data.api.OpenMeteoApiService
import xyz.attacktive.weatherd.data.api.dto.ForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.MetNoForecastResponseDto
import xyz.attacktive.weatherd.data.api.dto.toSnapshot
import xyz.attacktive.weatherd.data.provider.OpenMeteoWeatherProvider
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.render.sceneParamsFor

class HumidityWeatherMappingTest {
	private val json = Json { ignoreUnknownKeys = true }

	@Test
	fun `open meteo humidity survives real json decoding into haze`() {
		val cases = listOf<Pair<String?, Float>>(
			"92.5" to 0.125f,
			null to 0f,
			"null" to 0f,
			"0" to 0f
		)

		for ((humidityToken, expectedDensity) in cases) {
			val response = json.decodeFromString<ForecastResponseDto>(openMeteoBody(humidityToken))
			val snapshot = response.toSnapshot()
			val params = sceneParamsFor(snapshot, snapshot.observedAtEpochSeconds)

			assertFalse(snapshot.observation.condition.fog)
			assertEquals(0.0, snapshot.observation.precipitationMillimeters, 0.0001)
			assertEquals(expectedDensity, params.humidityHazeDensity, 0.0001f)
		}
	}

	@Test
	fun `met norway humidity survives real json decoding into haze`() {
		val cases = listOf<Pair<String?, Float>>(
			"92.5" to 0.125f,
			null to 0f,
			"null" to 0f,
			"0" to 0f
		)

		for ((humidityToken, expectedDensity) in cases) {
			val response = json.decodeFromString<MetNoForecastResponseDto>(metNoBody(humidityToken))
			val snapshot = response.toSnapshot(metNoSunResponse())
			val params = sceneParamsFor(snapshot, snapshot.observedAtEpochSeconds)

			assertFalse(snapshot.observation.condition.fog)
			assertEquals(0.0, snapshot.observation.precipitationMillimeters, 0.0001)
			assertEquals(expectedDensity, params.humidityHazeDensity, 0.0001f)
		}
	}

	@Test
	fun `requested humidity reaches scenes for Best Match and explicit ICON`() = runTest {
		val requests = mutableListOf<RequestContract>()
		val server = MockWebServer()
		server.dispatcher = object : Dispatcher() {
			override fun dispatch(request: RecordedRequest): MockResponse {
				val url = checkNotNull(request.requestUrl)
				val fields = url.queryParameter("current")
					?.split(',')
					?.filter { it.isNotBlank() }
					?.toSet()
					.orEmpty()
				requests += RequestContract(fields, url.queryParameter("models"))

				return MockResponse()
					.setResponseCode(200)
					.addHeader("Content-Type", "application/json")
					.setBody(selectedOpenMeteoBody(fields))
			}
		}
		server.start()

		try {
			val api = Retrofit.Builder()
				.baseUrl(server.url("/"))
				.addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
				.build()
				.create(OpenMeteoApiService::class.java)
			val provider = OpenMeteoWeatherProvider(api)

			val bestMatch = provider.current(37.5, 127.0)
			val icon = provider.current(37.5, 127.0, WeatherProviderType.DWD_ICON_GLOBAL)

			assertSceneContract(bestMatch, WeatherProviderType.OPEN_METEO, null)
			assertSceneContract(icon, WeatherProviderType.DWD_ICON_GLOBAL, "dwd_icon_global")
			assertEquals(2, requests.size)
			assertEquals(EXPECTED_CURRENT_FIELDS, requests[0].currentFields)
			assertNull(requests[0].model)
			assertEquals(EXPECTED_CURRENT_FIELDS, requests[1].currentFields)
			assertEquals("dwd_icon_global", requests[1].model)
		} finally {
			server.shutdown()
		}
	}

	private fun assertSceneContract(
		snapshot: xyz.attacktive.weatherd.domain.model.WeatherSnapshot,
		expectedProvider: WeatherProviderType,
		expectedModel: String?
	) {
		val params = sceneParamsFor(snapshot, snapshot.observedAtEpochSeconds)

		assertEquals(0.125f, params.humidityHazeDensity, 0.0001f)
		assertEquals(0.4f, params.cloudLayers?.low ?: -1f, 0.0001f)
		assertEquals(0.2f, params.cloudLayers?.mid ?: -1f, 0.0001f)
		assertEquals(0.1f, params.cloudLayers?.high ?: -1f, 0.0001f)
		assertEquals(expectedProvider, snapshot.source.provider)
		assertEquals(expectedModel, snapshot.source.model)
	}

	private fun openMeteoBody(relativeHumidityToken: String?): String {
		val currentMembers = mutableListOf(
			"\"time\": 1751889600",
			"\"weather_code\": 1",
			"\"is_day\": 1",
			"\"temperature_2m\": 20.0",
			"\"precipitation\": 0.0",
			"\"wind_speed_10m\": 8.0",
			"\"cloud_cover\": 40",
			"\"cloud_cover_low\": 40",
			"\"cloud_cover_mid\": 20",
			"\"cloud_cover_high\": 10"
		)
		if (relativeHumidityToken != null) {
			currentMembers += "\"relative_humidity_2m\": $relativeHumidityToken"
		}

		return """
			{
			  "latitude": 37.5,
			  "longitude": 127.0,
			  "current": {${currentMembers.joinToString(",")}},
			  "daily": {
			    "sunrise": [1751866500],
			    "sunset": [1751918700]
			  }
			}
		""".trimIndent()
	}

	private fun metNoBody(relativeHumidityToken: String?): String {
		val details = mutableListOf(
			"\"air_temperature\": 18.4",
			"\"cloud_area_fraction\": 40.0",
			"\"cloud_area_fraction_low\": 40.0",
			"\"cloud_area_fraction_medium\": 20.0",
			"\"cloud_area_fraction_high\": 10.0",
			"\"wind_speed\": 5.0"
		)
		if (relativeHumidityToken != null) {
			details += "\"relative_humidity\": $relativeHumidityToken"
		}

		return """
			{
			  "properties": {
			    "timeseries": [
			      {
			        "time": "2025-09-14T03:00:00Z",
			        "data": {
			          "instant": {"details": {${details.joinToString(",")}}},
			          "next_1_hours": {
			            "summary": {"symbol_code": "clearsky_day"},
			            "details": {"precipitation_amount": 0.0}
			          }
			        }
			      }
			    ]
			  }
			}
		""".trimIndent()
	}

	private fun selectedOpenMeteoBody(fields: Set<String>): String {
		val selected = mutableListOf("\"time\": 1751889600")
		for ((field, value) in OPEN_METEO_VALUES) {
			if (field in fields) {
				selected += "\"$field\": $value"
			}
		}

		return """
			{
			  "latitude": 37.5,
			  "longitude": 127.0,
			  "current": {${selected.joinToString(",")}},
			  "daily": {
			    "sunrise": [1751866500],
			    "sunset": [1751918700]
			  }
			}
		""".trimIndent()
	}

	private data class RequestContract(val currentFields: Set<String>, val model: String?)

	companion object {
		private val EXPECTED_CURRENT_FIELDS = setOf(
			"weather_code",
			"is_day",
			"temperature_2m",
			"precipitation",
			"wind_speed_10m",
			"cloud_cover",
			"cloud_cover_low",
			"cloud_cover_mid",
			"cloud_cover_high",
			"relative_humidity_2m"
		)

		private val OPEN_METEO_VALUES = linkedMapOf(
			"weather_code" to "1",
			"is_day" to "1",
			"temperature_2m" to "20.0",
			"precipitation" to "0.0",
			"wind_speed_10m" to "8.0",
			"cloud_cover" to "40",
			"cloud_cover_low" to "40",
			"cloud_cover_mid" to "20",
			"cloud_cover_high" to "10",
			"relative_humidity_2m" to "92.5"
		)
	}
}
