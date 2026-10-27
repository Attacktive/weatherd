package xyz.attacktive.weatherd.domain.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.CloudLayers
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.render.WeatherSceneTarget
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

class WeatherCacheRepositoryTest {
	@get:Rule
	val temporaryFolder = TemporaryFolder()

	@Test
	fun `new repository restores complete weather with original target and successful refresh time`() = runTest {
		val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temporaryFolder.newFile("cache.preferences_pb") }
		val logger = mockk<AppLogger>(relaxed = true)
		val cache = WeatherCacheRepository(store, Json, logger)
		assertNull(cache.load())
		val record = CachedWeather(
			snapshot = WeatherSnapshot(
				observation = WeatherObservation(conditionForWmoCode(63), true, 12.5, 3.0, 8.0, CloudCover(95, CloudLayers(90, 70, 20)), 88.0),
				observedAtEpochSeconds = 1_000_000L,
				sunriseEpochSeconds = 980_000L,
				sunsetEpochSeconds = 1_020_000L
			),
			target = WeatherSceneTarget.from(AppSettings(), GeoLocation(52.52, 13.40)),
			refreshedAtEpochSeconds = 1_000_060L,
			locationLabel = "Berlin"
		)
		cache.save(record)
		val restored = WeatherCacheRepository(store, Json, logger).load()
		assertEquals(record, restored)
	}

	@Test
	fun `malformed and unsupported cached records are unavailable rather than crashing startup`() = runTest {
		val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temporaryFolder.newFile("invalid.preferences_pb") }
		val cache = WeatherCacheRepository(store, Json, mockk(relaxed = true))
		for (value in listOf("not json", "{\"version\":999}")) {
			store.edit { it[stringPreferencesKey("last_successful_weather")] = value }
			assertNull(cache.load())
		}
	}
}
