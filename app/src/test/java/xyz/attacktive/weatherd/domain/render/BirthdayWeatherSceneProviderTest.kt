package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import android.content.Context
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.CloudCover
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.HolidayCelebration
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundState
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.conditionForWmoCode
import xyz.attacktive.weatherd.util.AppLogger

@OptIn(ExperimentalCoroutinesApi::class)
class BirthdayWeatherSceneProviderTest {
	private val context = mockk<Context>(relaxed = true) {
		every { getString(R.string.weather_clear_sky) } returns "Clear sky"
		every { getString(R.string.weather_rain) } returns "Rain"
	}

	private val locationRepository = mockk<LocationRepository>()
	private val weatherRepository = mockk<WeatherRepository>()
	private val reverseGeocodingRepository = mockk<ReverseGeocodingRepository>()
	private val persistedSettings = MutableStateFlow(AppSettings())
	private val photoState = MutableStateFlow(PhotoBackgroundState(emptySet()))
	private val settingsRepository = mockk<SettingsRepository> {
		every { defaults } returns AppSettings()
		every { settings } returns persistedSettings
	}

	private val photoBackgroundRepository = mockk<PhotoBackgroundRepository> {
		every { state } returns photoState
	}

	private val logger = mockk<AppLogger>(relaxed = true)

	private val applicationScope = CoroutineScope(UnconfinedTestDispatcher())
	private val provider by lazy {
		WeatherSceneProvider(context, locationRepository, weatherRepository, reverseGeocodingRepository, settingsRepository, photoBackgroundRepository, logger, applicationScope, weatherCacheFixture())
	}

	@After
	fun cancelSettingsCollection() {
		applicationScope.cancel()
	}

	@Test
	fun `birthday reaches live simulated and offline scenes independently of holiday preference`() = runTest {
		every { context.getString(R.string.birthday_greeting) } returns "Buon compleanno!"
		val now = Instant.parse("2026-11-07T22:00:00Z").epochSecond
		val settings = AppSettings(
			useDeviceLocation = false,
			manualLatitude = 0.0,
			manualLongitude = 0.0,
			seasonalCelebrationsEnabled = false,
			birthdayCelebrationEnabled = true,
			birthdayEveryDay = true
		)
		val fallback = WeatherSceneState(settings)
		val live = fallback.copy(snapshot = snapshotWith(weatherCode = 0, isDay = false).copy(
			observedAtEpochSeconds = now,
			sunriseEpochSeconds = now - 16 * 3600,
			sunsetEpochSeconds = now - 4 * 3600
		))
		for (state in listOf(fallback, live)) {
			val birthday = provider.paramsFor(now, state)
			val ordinary = provider.paramsFor(now, state.copy(settings = settings.copy(birthdayCelebrationEnabled = false)))
			assertEquals(DayPhase.NIGHT, birthday.dayPhase)
			assertEquals(HolidayCelebration.BIRTHDAY, birthday.holidayCelebration)
			assertEquals("Buon compleanno!", birthday.birthdayGreeting)
			assertEquals(HolidayCelebration.NONE, ordinary.holidayCelebration)
			assertEquals("", ordinary.birthdayGreeting)
			assertEquals(backdropSignature(ordinary), backdropSignature(birthday))
		}

		for (phase in DayPhase.entries) {
			val simulated = fallback.copy(settings = settings.copy(sceneSimulatorEnabled = true, sceneSimulatorActive = true, sceneSimulatorDayPhase = phase))
			val expected = if (phase == DayPhase.NIGHT) {
				HolidayCelebration.BIRTHDAY
			} else {
				HolidayCelebration.NONE
			}

			assertEquals(expected, provider.paramsFor(now, simulated, debugToolsAvailable = false).holidayCelebration)
		}

		coVerify(exactly = 0) { weatherRepository.current(any(), any()) }
	}

	@Test
	fun `birthday date reads captured settings and local date without altering the weather target`() {
		every { context.getString(R.string.birthday_greeting) } returns "Happy birthday!"
		val now = Instant.parse("2026-11-07T22:00:00Z")
		val today = java.time.MonthDay.from(now.atZone(java.time.ZoneId.systemDefault()))
		persistedSettings.value = AppSettings(
			birthdayCelebrationEnabled = true,
			birthdayDate = today,
			sceneSimulatorEnabled = true,
			sceneSimulatorActive = true,
			sceneSimulatorDayPhase = DayPhase.NIGHT
		)
		val captured = provider.sceneState.value
		assertEquals(HolidayCelebration.BIRTHDAY, provider.paramsFor(now.epochSecond, captured).holidayCelebration)
		persistedSettings.value = persistedSettings.value.copy(birthdayDate = null)
		assertEquals(HolidayCelebration.NONE, provider.paramsFor(now.epochSecond).holidayCelebration)
		assertEquals(HolidayCelebration.BIRTHDAY, provider.paramsFor(now.epochSecond, captured).holidayCelebration)
		assertEquals(HolidayCelebration.NONE, provider.paramsFor(now.plusSeconds(86_400).epochSecond, captured).holidayCelebration)
	}

	private fun snapshotWith(weatherCode: Int, isDay: Boolean) = WeatherSnapshot(
		observation = WeatherObservation(
			temperatureCelsius = 10.0,
			condition = conditionForWmoCode(weatherCode),
			cloudCover = CloudCover(0),
			windSpeedKilometersPerHour = 10.0,
			precipitationMillimeters = 0.0,
			isDay = isDay
		),
		observedAtEpochSeconds = 0,
		sunriseEpochSeconds = null,
		sunsetEpochSeconds = null,
		source = WeatherSource(WeatherProviderType.OPEN_METEO)
	)
}
