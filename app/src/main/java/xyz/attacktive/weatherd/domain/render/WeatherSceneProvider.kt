package xyz.attacktive.weatherd.domain.render

import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.moonPhaseFor
import xyz.attacktive.weatherd.domain.weather.weatherLabelFor
import xyz.attacktive.weatherd.util.AppLogger

/**
 * Shared source of truth for the current [SceneParams], so the live wallpaper and the in-app preview
 * never disagree about what to draw. Weather is fetched lazily and cached; the day phase is re-derived
 * from the clock on every read, so dawn→day→dusk→night transitions happen without a network round-trip.
 * Thread-safe: [refresh] runs off the render thread and publishes the snapshot through a volatile that
 * [paramsFor] reads.
 */
@Singleton
class WeatherSceneProvider @Inject constructor(private val locationRepository: LocationRepository, private val weatherRepository: WeatherRepository, private val reverseGeocodingRepository: ReverseGeocodingRepository, private val settingsRepository: SettingsRepository, private val logger: AppLogger) {
	@Volatile private var snapshot: WeatherSnapshot? = null
	@Volatile private var lastRefreshEpochSeconds = 0L
	@Volatile private var lastLocationKey: String? = null
	@Volatile private var backdropScene = BackdropScene.NONE
	@Volatile private var showWeatherLabel = true
	@Volatile private var showLocationLabel = false
	@Volatile private var temperatureUnit = TemperatureUnit.CELSIUS
	@Volatile private var locationLabel: String? = null
	@Volatile private var lastFix: GeoLocation? = null
	@Volatile private var geocodedKey: String? = null

	/** The scene to draw at [nowEpochSeconds]; a clock-lit clear sky until the first weather fetch lands. */
	fun paramsFor(nowEpochSeconds: Long): SceneParams {
		val snapshot = this.snapshot ?: return fallbackParams(nowEpochSeconds)

		return sceneParamsFor(snapshot, nowEpochSeconds, backdropScene, overlayLabels(snapshot))
	}

	/**
	 * Fetches fresh weather for the current location, unless a fetch succeeded within the user's configured
	 * refresh interval (bypass with [force]). A change in location settings (device↔manual, or a new city)
	 * also bypasses the interval, so the scene tracks the new place on the next refresh instead of waiting out
	 * the throttle. No-ops without a location fix or permission, leaving the last known scene in place.
	 */
	suspend fun refresh(nowEpochSeconds: Long, force: Boolean = false) {
		val settings = settingsRepository.settings.first()

		// Render settings are captured before the throttle: they're display choices, not weather, so even a throttled refresh must adopt them.
		backdropScene = settings.backdropScene
		showWeatherLabel = settings.showWeatherLabel
		showLocationLabel = settings.showLocationLabel
		temperatureUnit = settings.temperatureUnit
		refreshLocationLabel(settings)

		val locationKey = locationKey(settings)
		val locationChanged = locationKey != lastLocationKey
		val minRefreshSeconds = settings.updateIntervalMinutes * SECONDS_PER_MINUTE
		if (!force && !locationChanged && nowEpochSeconds - lastRefreshEpochSeconds < minRefreshSeconds) {
			return
		}

		val location = resolveLocation(settings)
		if (location == null) {
			logger.debug(TAG, "no location fix; keeping ${if (snapshot == null) "fallback scene" else "last snapshot"}")
			return
		}

		// The fix is remembered and the label refreshed again now that one exists — the first refresh has nothing cached for the pre-throttle pass to geocode.
		lastFix = location
		refreshLocationLabel(settings)

		weatherRepository.current(location.latitude, location.longitude).onSuccess {
			snapshot = it
			lastRefreshEpochSeconds = nowEpochSeconds
			lastLocationKey = locationKey
			logger.debug(TAG, "weather refreshed: code=${it.observation.weatherCode}, cloud=${it.observation.cloudCoverPercent}%")
		}
	}

	/** Manual coordinates win only when the user opted out of device location and actually set a place; otherwise the device fix. */
	private suspend fun resolveLocation(settings: AppSettings): GeoLocation? {
		val manual = manualLocation(settings)

		return if (!settings.useDeviceLocation && manual != null) {
			manual
		} else {
			locationRepository.currentLocation()
		}
	}

	private fun manualLocation(settings: AppSettings): GeoLocation? {
		val latitude = settings.manualLatitude
		val longitude = settings.manualLongitude

		return if (latitude != null && longitude != null) {
			GeoLocation(latitude, longitude)
		} else {
			null
		}
	}

	/**
	 * Keeps [locationLabel] current: the user's own words in manual mode, a reverse-geocoded place name in
	 * device mode. Geocoding is skipped while the label is toggled off and cached per fix, so a place is
	 * looked up once — a failed lookup leaves the label null and retries on the next refresh.
	 */
	private suspend fun refreshLocationLabel(settings: AppSettings) {
		if (!settings.useDeviceLocation) {
			locationLabel = settings.manualLocationLabel
			return
		}

		if (!settings.showLocationLabel) {
			return
		}

		val fix = lastFix ?: return
		val fixKey = "${fix.latitude},${fix.longitude}"
		if (fixKey == geocodedKey && locationLabel != null) {
			return
		}

		locationLabel = reverseGeocodingRepository.placeName(fix.latitude, fix.longitude)
		geocodedKey = fixKey
	}

	/** The formatted overlay lines, or null when nothing is toggled on — the renderer skips the text pass entirely. */
	private fun overlayLabels(snapshot: WeatherSnapshot): OverlayLabels? {
		val weather = if (showWeatherLabel) {
			weatherText(snapshot.observation)
		} else {
			null
		}

		val location = if (showLocationLabel) {
			locationLabel
		} else {
			null
		}

		return if (weather == null && location == null) {
			null
		} else {
			OverlayLabels(weather, location)
		}
	}

	/** "Rain · 10°" — or the bare temperature when the code falls outside the label table. */
	private fun weatherText(observation: WeatherObservation): String {
		val label = weatherLabelFor(observation.weatherCode)
		val temperature = temperatureUnit.format(observation.temperatureCelsius)

		return if (label == null) {
			temperature
		} else {
			"$label · $temperature"
		}
	}

	/** A cheap signature of the location inputs; changing it (device↔manual, or a new city) triggers a refetch even mid-interval. */
	private fun locationKey(settings: AppSettings) = if (settings.useDeviceLocation) {
		"device"
	} else {
		"${settings.manualLatitude},${settings.manualLongitude}"
	}

	/** Until weather loads we still want the right time of day — and the right moon — so lean on the local wall clock. */
	private fun fallbackParams(nowEpochSeconds: Long): SceneParams {
		val phase = when (LocalTime.now().hour) {
			in DAWN_HOUR until DAY_HOUR -> DayPhase.DAWN
			in DAY_HOUR until DUSK_HOUR -> DayPhase.DAY
			in DUSK_HOUR until NIGHT_HOUR -> DayPhase.DUSK
			else -> DayPhase.NIGHT
		}

		return SceneParams(dayPhase = phase, cloudiness = 0.05f, fogDensity = 0f, precipitation = null, thunder = false, windFactor = 0.2f, moonPhase = moonPhaseFor(nowEpochSeconds), backdropScene = backdropScene)
	}

	companion object {
		private const val TAG = "WeatherSceneProvider"
		private const val SECONDS_PER_MINUTE = 60L
		private const val DAWN_HOUR = 5
		private const val DAY_HOUR = 7
		private const val DUSK_HOUR = 17
		private const val NIGHT_HOUR = 19
	}
}
