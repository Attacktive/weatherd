package xyz.attacktive.weatherd.domain.render

import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import xyz.attacktive.weatherd.debugToolsEnabled
import xyz.attacktive.weatherd.di.ApplicationScope
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.GeoLocation
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.WeatherObservation
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSnapshot
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.moonPhaseFor
import xyz.attacktive.weatherd.domain.weather.weatherLabelFor
import xyz.attacktive.weatherd.util.AppLogger

data class WeatherSceneStatus(val locationLabel: String? = null, val lastRefreshEpochSeconds: Long? = null, val weatherSource: WeatherSource? = null)

/** Immutable scene inputs; changing the selected scene never needs to acquire weather. */
data class WeatherSceneState(
	val settings: AppSettings,
	val snapshot: WeatherSnapshot? = null,
	val snapshotTarget: WeatherSceneTarget? = null,
	val deviceLocation: GeoLocation? = null,
	val photoRevision: Int = 0,
	val locationLabel: String? = null,
	val locationLabelTarget: WeatherSceneTarget? = null
) {
	val simulatorActive: Boolean
		get() = sceneSimulatorOverridesWeather(settings.sceneSimulatorActive, settings.sceneSimulatorEnabled, debugToolsEnabled)
}

private data class WeatherRefreshTarget(
	val useDeviceLocation: Boolean,
	val latitude: Double?,
	val longitude: Double?,
	val provider: WeatherProviderType,
	val fallbackProvider: WeatherProviderType,
	val showLocationLabel: Boolean,
	val resolveLocationName: Boolean
)

private data class WeatherRequestKey(
	val latitude: Double,
	val longitude: Double,
	val provider: WeatherProviderType,
	val fallbackProvider: WeatherProviderType
)

private data class WeatherRequestToken(
	val key: WeatherRequestKey,
	val generation: Long,
	val sequence: Long
)

internal fun sceneSimulatorOverridesWeather(
	sceneSimulatorActive: Boolean,
	sceneSimulatorEnabled: Boolean,
	debugToolsEnabled: Boolean
) = sceneSimulatorActive && (sceneSimulatorEnabled || debugToolsEnabled)

/**
 * Shared source of truth for the current [SceneParams], so the live wallpaper and the in-app preview never disagree about what to draw. Live weather is fetched lazily and cached; while the persisted scene simulator is active, its preset, phase and progress replace those meteorological fields for both consumers.
 * Settings and weather publish independently into one immutable input snapshot, shared by both rendering surfaces.
 */
@Singleton
class WeatherSceneProvider @Inject constructor(
	@ApplicationContext private val context: Context,
	private val locationRepository: LocationRepository,
	private val weatherRepository: WeatherRepository,
	private val reverseGeocodingRepository: ReverseGeocodingRepository,
	private val settingsRepository: SettingsRepository,
	private val photoBackgroundRepository: PhotoBackgroundRepository,
	private val logger: AppLogger,
	@ApplicationScope private val applicationScope: CoroutineScope
) {
	private val _status = MutableStateFlow(WeatherSceneStatus())
	val status: StateFlow<WeatherSceneStatus> = _status.asStateFlow()

	private val _sceneState = MutableStateFlow(WeatherSceneState(settingsRepository.defaults))
	val sceneState: StateFlow<WeatherSceneState> = _sceneState.asStateFlow()
	private val snapshot: WeatherSnapshot?
		get() = _sceneState.value.snapshot

	@Volatile private var lastRefreshEpochSeconds = 0L
	@Volatile private var lastLocationKey: String? = null
	@Volatile private var lastAttemptedWeatherProvider: WeatherProviderType? = null
	@Volatile private var lastAttemptedWeatherFallbackProvider: WeatherProviderType? = null
	private val refreshLock = Any()
	private var pendingRefreshTarget: WeatherRefreshTarget? = null
	private var pendingRefresh: Deferred<Result<Unit>>? = null
	private val weatherRequestLock = Any()
	private var activeWeatherRequestKey: WeatherRequestKey? = null
	private var weatherRequestGeneration = 0L
	private var weatherRequestSequence = 0L
	private var lastPublishedWeatherRequestSequence = 0L
	private val locationLabel: String?
		get() = _sceneState.value.locationLabel

	private val deviceFixLock = Any()
	private val lastDeviceFix: GeoLocation?
		get() = _sceneState.value.deviceLocation
	@Volatile private var lastRefreshLocation: GeoLocation? = null
	@Volatile private var geocodedKey: String? = null

	init {
		applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
			combine(settingsRepository.settings, photoBackgroundRepository.state) { settings, photos ->
				val photoRevision = if (settings.backdropScene == BackdropScene.PHOTO) {
					photos.revision
				} else {
					0
				}

				settings to photoRevision
			}.collect { (settings, revision) ->
				_sceneState.update { it.copy(settings = settings, photoRevision = revision) }
			}
		}
	}

	/** The scene to draw at [nowEpochSeconds]; a clock-lit clear sky until the first weather fetch lands. */
	fun paramsFor(nowEpochSeconds: Long, state: WeatherSceneState = sceneState.value, debugToolsAvailable: Boolean = debugToolsEnabled): SceneParams = with(state.settings) {
		if (sceneSimulatorOverridesWeather(sceneSimulatorActive, sceneSimulatorEnabled, debugToolsAvailable)) {
			return@with simulatorParams(nowEpochSeconds, state)
		}

		val snapshot = state.snapshot ?: return@with fallbackParams(nowEpochSeconds, state)
		if (state.snapshotTarget?.matchesWeather(state.settings, state.deviceLocation) != true) {
			return@with fallbackParams(nowEpochSeconds, state)
		}

		sceneParamsFor(
			snapshot = snapshot,
			nowEpochSeconds = nowEpochSeconds,
			backdropScene = backdropScene,
			photoRevision = state.photoRevision,
			overlayLabels = overlayLabels(snapshot, state),
			precipitationScale = precipitationIntensityScale,
			windScale = windIntensityScale,
			cloudScale = cloudIntensityScale,
			cloudSizeScale = cloudSizeScale,
			cloudCountScale = cloudCountScale,
			cloudContrastScale = cloudContrastScale,
			skyBrightnessScale = skyBrightnessScale,
			nightBrightnessScale = nightBrightnessScale,
			skySaturationScale = skySaturationScale,
			skyColorPreset = skyColorPreset,
			sunVisible = sunVisible,
			moonVisible = moonVisible,
			sunSizeScale = sunSizeScale,
			sunColorPreset = sunColorPreset,
			lensFlareEnabled = lensFlareEnabled,
			lensFlareMotionEnabled = lensFlareMotionEnabled,
			glassDropletsEnabled = glassDropletsEnabled
		)
	}

	/**
	 * Fetches fresh weather for the current location, unless a fetch succeeded within the user's configured refresh interval; [force] bypasses only that weather throttle and keeps the normal device-location freshness policy.
	 * A change in location settings (device↔manual, or a new city) or weather provider also bypasses the interval, so the scene tracks the new source on the next refresh instead of waiting out the throttle.
	 * No-ops without a location fix or permission, leaving the last known scene in place.
	 */
	suspend fun refresh(nowEpochSeconds: Long, force: Boolean = false, resolveLocationName: Boolean = false) {
		if (force) {
			refreshWithResult(nowEpochSeconds, force = true, resolveLocationName = resolveLocationName)
			return
		}

		val settings = settingsRepository.settings.first()
		val target = WeatherRefreshTarget(
			useDeviceLocation = settings.useDeviceLocation,
			latitude = settings.manualLatitude,
			longitude = settings.manualLongitude,
			provider = settings.weatherProvider,
			fallbackProvider = settings.weatherFallbackProvider,
			showLocationLabel = settings.showLocationLabel,
			resolveLocationName = resolveLocationName
		)

		val request = synchronized(refreshLock) {
			pendingRefresh?.takeIf { pendingRefreshTarget == target && !it.isCompleted } ?: applicationScope.async(start = CoroutineStart.LAZY) {
				refreshWithResult(nowEpochSeconds, resolveLocationName = resolveLocationName)
			}.also { pending ->
				pendingRefreshTarget = target
				pendingRefresh = pending
				pending.invokeOnCompletion {
					synchronized(refreshLock) {
						if (pendingRefresh === pending) {
							pendingRefresh = null
							pendingRefreshTarget = null
						}
					}
				}
			}
		}

		request.await()
	}

	/** The refresh result for background callers that need to distinguish a provider failure from a successful or intentionally skipped refresh. */
	internal suspend fun refreshWithResult(nowEpochSeconds: Long, force: Boolean = false, resolveLocationName: Boolean = false, debugToolsAvailable: Boolean = debugToolsEnabled): Result<Unit> {
		if (force) {
			synchronized(refreshLock) {
				pendingRefresh = null
				pendingRefreshTarget = null
			}
		}

		val settings = settingsRepository.settings.first()
		if (sceneSimulatorOverridesWeather(settings.sceneSimulatorActive, settings.sceneSimulatorEnabled, debugToolsAvailable)) {
			refreshSimulatorStatus(settings, resolveLocationName)
			return Result.success(Unit)
		}

		refreshLocationLabel(settings, resolveLocationName)

		val locationKey = locationKey(settings)
		if (weatherRefreshIsThrottled(settings, locationKey, nowEpochSeconds, force)) {
			return Result.success(Unit)
		}

		val location = resolveLocation(settings)
		if (location == null) {
			logger.debug(TAG, "no location fix; keeping ${weatherFallbackDescription()}")
			return Result.success(Unit)
		}

		if (!weatherRequestIsCurrent(settings)) {
			logger.debug(TAG, "discarding obsolete weather request before location update")
			return Result.success(Unit)
		}

		// The device fix is remembered and the label refreshed again now that one exists — the first refresh has nothing cached for the pre-throttle pass to geocode.
		rememberDeviceFix(settings, location)
		refreshLocationLabel(settings, resolveLocationName)

		if (!weatherRequestIsCurrent(settings)) {
			logger.debug(TAG, "discarding obsolete weather request before fetch")
			return Result.success(Unit)
		}

		/*
		 * A provider change is an immediate-refresh trigger, not a retry policy.
		 * Consume it when the request is attempted so a failing provider does not bypass the normal interval on every visibility change.
		 */
		lastAttemptedWeatherProvider = settings.weatherProvider
		lastAttemptedWeatherFallbackProvider = settings.weatherFallbackProvider
		val request = beginWeatherRequest(settings, location)
		val weatherResult = weatherRepository.current(location.latitude, location.longitude)
		if (weatherResult.isFailure) {
			return weatherResult.map { }
		}

		val latestSettings = settingsRepository.settings.first()

		return weatherResult.map { weather ->
			if (!publishWeatherResponse(request, settings, latestSettings, weather, nowEpochSeconds, locationKey, location)) {
				logger.debug(TAG, "discarding obsolete weather response")
				return@map
			}

			val cloudCover = weather.observation.cloudCover
			logger.debug(TAG, "weather refreshed: provider=${weather.source.provider}, condition=${weather.observation.condition.label}, cloud=${cloudCover.totalPercent}%${cloudLayerDescription(weather)}")
		}
	}

	private fun weatherRefreshIsThrottled(settings: AppSettings, locationKey: String, nowEpochSeconds: Long, force: Boolean): Boolean {
		val state = sceneState.value
		val locationChanged = locationKey != lastLocationKey || state.snapshotTarget?.matchesLocation(settings, state.deviceLocation) == false
		val weatherProviderChanged = settings.weatherProvider != lastAttemptedWeatherProvider || settings.weatherFallbackProvider != lastAttemptedWeatherFallbackProvider
		val minRefreshSeconds = settings.updateIntervalMinutes * SECONDS_PER_MINUTE

		return !force && !locationChanged && !weatherProviderChanged && nowEpochSeconds - lastRefreshEpochSeconds < minRefreshSeconds
	}

	private fun weatherFallbackDescription() = if (snapshot == null) {
		"fallback scene"
	} else {
		"last snapshot"
	}

	private fun cloudLayerDescription(weather: WeatherSnapshot): String {
		val layers = weather.observation.cloudCover.layers ?: return ", layers=unavailable"

		return ", low=${layers.lowPercent}%, mid=${layers.midPercent}%, high=${layers.highPercent}%"
	}

	private fun beginWeatherRequest(settings: AppSettings, location: GeoLocation) = synchronized(weatherRequestLock) {
		val key = WeatherRequestKey(
			latitude = location.latitude,
			longitude = location.longitude,
			provider = settings.weatherProvider,
			fallbackProvider = settings.weatherFallbackProvider
		)
		if (key != activeWeatherRequestKey) {
			activeWeatherRequestKey = key
			weatherRequestGeneration += 1
			lastPublishedWeatherRequestSequence = 0
		}

		weatherRequestSequence += 1

		WeatherRequestToken(
			key = key,
			generation = weatherRequestGeneration,
			sequence = weatherRequestSequence
		)
	}

	private fun publishWeatherResponse(
		request: WeatherRequestToken,
		requestSettings: AppSettings,
		latestSettings: AppSettings,
		weather: WeatherSnapshot,
		nowEpochSeconds: Long,
		locationKey: String,
		location: GeoLocation
	): Boolean {
		if (!sameWeatherRequest(requestSettings, latestSettings)) {
			return false
		}

		return synchronized(weatherRequestLock) {
			if (request.generation != weatherRequestGeneration || request.key != activeWeatherRequestKey || request.sequence <= lastPublishedWeatherRequestSequence) {
				false
			} else {
				lastPublishedWeatherRequestSequence = request.sequence
				val target = WeatherSceneTarget.from(requestSettings, location)
				_sceneState.update { it.copy(snapshot = weather, snapshotTarget = target) }

				lastRefreshEpochSeconds = nowEpochSeconds
				lastLocationKey = locationKey
				lastRefreshLocation = location
				publishStatus(requestSettings)
				true
			}
		}
	}


	/** The persisted simulator scene, carrying the same display preferences as live weather while replacing its meteorological fields. */
	private fun simulatorParams(nowEpochSeconds: Long, state: WeatherSceneState) = with(state.settings) {
		debugSceneParams(
			preset = SCENE_PRESETS[sceneSimulatorPresetIndex.coerceIn(0, SCENE_PRESETS.lastIndex)],
			dayPhase = sceneSimulatorDayPhase,
			precipitationScale = precipitationIntensityScale,
			windScale = windIntensityScale,
			cloudScale = cloudIntensityScale,
			cloudSizeScale = cloudSizeScale,
			cloudCountScale = cloudCountScale,
			cloudContrastScale = cloudContrastScale,
			skyBrightnessScale = skyBrightnessScale,
			nightBrightnessScale = nightBrightnessScale,
			skySaturationScale = skySaturationScale,
			skyColorPreset = skyColorPreset,
			moonPhase = moonPhaseFor(nowEpochSeconds),
			sunVisible = sunVisible,
			moonVisible = moonVisible,
			sunSizeScale = sunSizeScale,
			sunColorPreset = sunColorPreset,
			lensFlareEnabled = lensFlareEnabled,
			celestialProgress = sceneSimulatorCelestialProgress.coerceIn(0f, 1f),
			lensFlareMotionEnabled = lensFlareMotionEnabled,
			glassDropletsEnabled = glassDropletsEnabled
		)
			.copy(backdropScene = backdropScene, photoRevision = state.photoRevision)
	}

	/** Manual coordinates win only when the user opted out of device location and actually set a place; otherwise the device fix. */
	private suspend fun resolveLocation(settings: AppSettings): GeoLocation? {
		val manual = selectedManualLocation(settings)
		if (manual != null) {
			return manual
		}

		return locationRepository.currentLocation()
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

	private fun selectedManualLocation(settings: AppSettings) = if (settings.useDeviceLocation) {
		null
	} else {
		manualLocation(settings)
	}

	/**
	 * Keeps [locationLabel] current: the user's own words in manual mode, a reverse-geocoded place name in device mode.
	 * Device geocoding is cached per fix. It normally runs only for the wallpaper label, while Settings can explicitly request the same place name for its status row.
	 */
	private suspend fun refreshLocationLabel(settings: AppSettings, resolveForStatus: Boolean) {
		if (selectedManualLocation(settings) != null) {
			publishLocationLabel(settings, settings.manualLocationLabel)
			geocodedKey = null
			publishStatus(settings)
			return
		}

		if (!settings.showLocationLabel && !resolveForStatus) {
			return
		}

		val fix = lastDeviceFix
		if (fix == null) {
			publishLocationLabel(settings, null)
			geocodedKey = null
			publishStatus(settings)
			return
		}

		val fixKey = locationFixKey(fix)
		if (fixKey == geocodedKey && locationLabel != null) {
			publishStatus(settings)
			return
		}

		val label = reverseGeocodingRepository.placeName(fix.latitude, fix.longitude)
		publishLocationLabel(settings, label, fix)
		geocodedKey = fixKey
		publishStatus(settings)
	}

	private fun publishLocationLabel(settings: AppSettings, label: String?, deviceLocation: GeoLocation? = null) {
		val target = WeatherSceneTarget.from(settings, deviceLocation)
		_sceneState.update { it.copy(locationLabel = label, locationLabelTarget = target) }
	}

	private fun displayedLocationLabel(state: WeatherSceneState): String? {
		val settings = state.settings
		if (!WeatherSceneTarget.selectsDeviceLocation(settings)) {
			return settings.manualLocationLabel
		}

		return state.locationLabel.takeIf { state.locationLabelTarget?.matchesLocation(settings, state.deviceLocation) == true }
	}

	private suspend fun refreshSimulatorStatus(settings: AppSettings, resolveLocationName: Boolean) {
		if (!resolveLocationName) {
			return
		}

		refreshStatusLocation(settings)
	}

	private suspend fun refreshStatusLocation(settings: AppSettings) {
		val location = resolveLocation(settings)
		if (location != null) {
			rememberDeviceFix(settings, location)
		}

		refreshLocationLabel(settings, resolveForStatus = true)
	}

	private fun rememberDeviceFix(settings: AppSettings, location: GeoLocation) = synchronized(deviceFixLock) {
		if (selectedManualLocation(settings) != null) {
			return@synchronized
		}

		if (location != lastDeviceFix) {
			_sceneState.update { it.copy(deviceLocation = location, locationLabel = null, locationLabelTarget = null) }

			geocodedKey = null
		}
	}

	private fun publishStatus(settings: AppSettings) {
		val manual = selectedManualLocation(settings)
		val currentLocation = manual ?: lastDeviceFix
		val currentFixKey = currentLocation?.let(::locationFixKey)
		val statusLabel = manual?.let { settings.manualLocationLabel } ?: deviceLocationLabel(currentFixKey)
		val hasCurrentSnapshot = currentLocation != null && currentLocation == lastRefreshLocation
		val lastUpdated = lastRefreshEpochSeconds.takeIf { hasCurrentSnapshot }
		val weatherSource = snapshot?.source.takeIf { hasCurrentSnapshot }

		_status.value = WeatherSceneStatus(
			locationLabel = statusLabel,
			lastRefreshEpochSeconds = lastUpdated,
			weatherSource = weatherSource
		)
	}

	private fun deviceLocationLabel(currentFixKey: String?) = locationLabel.takeIf { currentFixKey != null && currentFixKey == geocodedKey }

	private fun locationFixKey(location: GeoLocation) = "${location.latitude},${location.longitude}"

	private suspend fun weatherRequestIsCurrent(settings: AppSettings) = sameWeatherRequest(settings, settingsRepository.settings.first())

	private fun sameWeatherRequest(first: AppSettings, second: AppSettings) = first.weatherProvider == second.weatherProvider &&
		first.weatherFallbackProvider == second.weatherFallbackProvider &&
		first.useDeviceLocation == second.useDeviceLocation &&
		first.manualLatitude == second.manualLatitude &&
		first.manualLongitude == second.manualLongitude

	/** The formatted overlay lines, or null when nothing is toggled on — the renderer skips the text pass entirely. */
	private fun overlayLabels(snapshot: WeatherSnapshot, state: WeatherSceneState): OverlayLabels? = with(state.settings) {
		val weather = if (showWeatherLabel) {
			weatherText(snapshot.observation, temperatureUnit)
		} else {
			null
		}

		val location = if (showLocationLabel) {
			displayedLocationLabel(state)
		} else {
			null
		}

		if (weather == null && location == null) {
			null
		} else {
			OverlayLabels(weather, location)
		}
	}

	/** "Rain · 10°" — or the bare temperature when the provider has no matching display label. */
	private fun weatherText(observation: WeatherObservation, temperatureUnit: TemperatureUnit): String {
		val labelResId = weatherLabelFor(observation.condition.label)
		val temperature = temperatureUnit.format(observation.temperatureCelsius)

		return if (labelResId == null) {
			temperature
		} else {
			"${context.getString(labelResId)} · $temperature"
		}
	}

	/** A cheap signature of the effective location source; changing device↔manual or choosing a new city triggers a refetch even mid-interval. */
	private fun locationKey(settings: AppSettings) = selectedManualLocation(settings)?.let(::locationFixKey) ?: "device"

	/** Until weather loads we still want the right time of day — and the right moon — so lean on the local wall clock. */
	private fun fallbackParams(nowEpochSeconds: Long, state: WeatherSceneState): SceneParams = with(state.settings) {
		val phase = when (LocalTime.now().hour) {
			in DAWN_HOUR until DAY_HOUR -> DayPhase.DAWN
			in DAY_HOUR until DUSK_HOUR -> DayPhase.DAY
			in DUSK_HOUR until NIGHT_HOUR -> DayPhase.DUSK
			else -> DayPhase.NIGHT
		}

		SceneParams(
			dayPhase = phase,
			cloudiness = 0.05f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			moonPhase = moonPhaseFor(nowEpochSeconds),
			backdropScene = backdropScene,
			photoRevision = state.photoRevision,
			precipitationScale = precipitationIntensityScale,
			windScale = windIntensityScale,
			cloudScale = cloudIntensityScale,
			cloudSizeScale = cloudSizeScale,
			cloudCountScale = cloudCountScale,
			cloudContrastScale = cloudContrastScale,
			skyBrightnessScale = skyBrightnessScale,
			nightBrightnessScale = nightBrightnessScale,
			skySaturationScale = skySaturationScale,
			skyColorPreset = skyColorPreset,
			sunVisible = sunVisible,
			moonVisible = moonVisible,
			sunSizeScale = sunSizeScale,
			sunColorPreset = sunColorPreset,
			lensFlareEnabled = lensFlareEnabled,
			lensFlareMotionEnabled = lensFlareMotionEnabled,
			glassDropletsEnabled = glassDropletsEnabled,
			overlayLabels = fallbackOverlayLabels(state)
		)
	}

	private fun fallbackOverlayLabels(state: WeatherSceneState): OverlayLabels? {
		val location = displayedLocationLabel(state).takeIf { state.settings.showLocationLabel }

		return if (location == null) {
			null
		} else {
			OverlayLabels(weather = null, location = location)
		}
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
