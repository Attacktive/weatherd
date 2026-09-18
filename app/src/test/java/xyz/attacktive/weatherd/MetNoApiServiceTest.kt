package xyz.attacktive.weatherd

import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.attacktive.weatherd.di.createMetNoApiService

class MetNoApiServiceTest {
	@Test
	fun `retrofit wiring sends MET path queries and user agent`() = runTest {
		val server = MockWebServer()
		server.start()

		try {
			server.enqueue(jsonResponse(FORECAST_BODY))
			val api = createMetNoApiService(
				OkHttpClient(),
				Json { ignoreUnknownKeys = true },
				server.url("/").toString()
			)

			val response = api.forecast("0.0006", "0.0001")
			val request = server.takeRequest()

			assertEquals("/weatherapi/locationforecast/2.0/compact?lat=0.0006&lon=0.0001", request.path)
			assertTrue(request.getHeader("User-Agent")?.startsWith("weatherd/") == true)
			assertEquals("cloudy", response.properties.timeseries.first().data?.nextOneHour?.summary?.symbolCode)
			assertEquals(2, response.properties.timeseries.size)
		} finally {
			server.shutdown()
		}
	}

	@Test
	fun `sunrise endpoint wiring parses solar events`() = runTest {
		val server = MockWebServer()
		server.start()

		try {
			server.enqueue(jsonResponse(SUNRISE_BODY))
			val api = createMetNoApiService(
				OkHttpClient(),
				Json { ignoreUnknownKeys = true },
				server.url("/").toString()
			)

			val response = api.sunrise("37.5000", "127.0000", "2025-09-14")
			val request = server.takeRequest()

			assertEquals("/weatherapi/sunrise/3.0/sun?lat=37.5000&lon=127.0000&date=2025-09-14", request.path)
			assertEquals("2025-09-14T06:00:00Z", response.properties.sunrise?.time)
			assertTrue(response.properties.solarnoon?.visible == true)
		} finally {
			server.shutdown()
		}
	}

	@Test
	fun `MET client reuses a fresh cached response`() = runTest {
		val server = MockWebServer()
		val cache = Cache(Files.createTempDirectory("weatherd-met-cache").toFile(), 1024L * 1024L)
		server.start()

		try {
			server.enqueue(
				jsonResponse(FORECAST_BODY)
					.addHeader("Date", "Fri, 18 Sep 2026 06:00:00 GMT")
					.addHeader("Expires", "Fri, 18 Sep 2099 06:30:00 GMT")
					.addHeader("Last-Modified", "Fri, 18 Sep 2026 05:30:00 GMT")
			)

			val api = createMetNoApiService(
				OkHttpClient(),
				Json { ignoreUnknownKeys = true },
				server.url("/").toString(),
				cache
			)

			api.forecast("37.5000", "127.0000")
			api.forecast("37.5000", "127.0000")

			assertEquals(1, server.requestCount)
		} finally {
			cache.close()
			server.shutdown()
		}
	}

	@Test
	fun `MET client revalidates a stale cached response with last modified`() = runTest {
		val server = MockWebServer()
		val cacheDirectory = Files.createTempDirectory("weatherd-met-revalidation").toFile()
		val cache = Cache(cacheDirectory, 1024L * 1024L)
		server.start()

		try {
			server.enqueue(
				jsonResponse(FORECAST_BODY)
					.addHeader("Date", "Fri, 18 Sep 2026 05:00:00 GMT")
					.addHeader("Expires", "Fri, 18 Sep 2026 05:01:00 GMT")
					.addHeader("Last-Modified", "Fri, 18 Sep 2026 04:30:00 GMT")
			)

			server.enqueue(MockResponse().setResponseCode(304))

			val api = createMetNoApiService(
				OkHttpClient(),
				Json { ignoreUnknownKeys = true },
				server.url("/").toString(),
				cache
			)

			api.forecast("37.5000", "127.0000")
			val revalidated = api.forecast("37.5000", "127.0000")
			val firstRequest = server.takeRequest()
			val secondRequest = server.takeRequest()

			assertNull(firstRequest.getHeader("If-Modified-Since"))
			assertEquals("Fri, 18 Sep 2026 04:30:00 GMT", secondRequest.getHeader("If-Modified-Since"))
			assertEquals("cloudy", revalidated.properties.timeseries.first().data?.nextOneHour?.summary?.symbolCode)
		} finally {
			cache.close()
			cacheDirectory.deleteRecursively()
			server.shutdown()
		}
	}

	private fun jsonResponse(body: String) = MockResponse()
		.setResponseCode(200)
		.addHeader("Content-Type", "application/json")
		.setBody(body)

	companion object {
		private val FORECAST_BODY = """
			{
			  "type": "Feature",
			  "properties": {
			    "timeseries": [
			      {
			        "time": "2025-09-14T03:00:00Z",
			        "data": {
			          "instant": {
			            "details": {
			              "air_temperature": 18.4,
			              "cloud_area_fraction": 77.8,
			              "wind_speed": 5.0
			            }
			          },
			          "next_1_hours": {
			            "summary": {"symbol_code": "cloudy"},
			            "details": {"precipitation_amount": 0.0}
			          }
			        }
			      },
			      {
			        "time": "2025-09-20T03:00:00Z",
			        "data": {
			          "instant": {}
			        }
			      }
			    ]
			  }
			}
		""".trimIndent()

		private val SUNRISE_BODY = """
			{
			  "type": "Feature",
			  "properties": {
			    "sunrise": {"time": "2025-09-14T06:00:00Z", "azimuth": 90.0},
			    "sunset": {"time": "2025-09-14T18:00:00Z", "azimuth": 270.0},
			    "solarnoon": {"time": "2025-09-14T12:00:00Z", "visible": true},
			    "solarmidnight": {"time": "2025-09-14T00:00:00Z", "visible": false}
			  }
			}
		""".trimIndent()
	}
}
