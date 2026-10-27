package xyz.attacktive.weatherd.domain.render

import java.time.Instant
import java.time.ZoneId
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
import kotlinx.coroutines.flow.filterNotNull
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
import xyz.attacktive.weatherd.domain.model.normalizeRainbowSizeScale
import xyz.attacktive.weatherd.domain.repository.CachedWeather
import xyz.attacktive.weatherd.domain.repository.WeatherCacheRepository
import xyz.attacktive.weatherd.domain.repository.LocationRepository
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.ReverseGeocodingRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.domain.repository.WeatherRepository
import xyz.attacktive.weatherd.domain.weather.LocalSolarTimesCache
import xyz.attacktive.weatherd.domain.weather.dayPhaseFor
import xyz.attacktive.weatherd.domain.weather.dayPhaseProgressFor
import xyz.attacktive.weatherd.domain.weather.moonPhaseFor
import xyz.attacktive.weatherd.domain.weather.weatherLabelFor
import xyz.attacktive.weatherd.util.AppLogger

data class WeatherSceneStatus(
	val locationLabel: String? = null,
	val lastRefreshEpochSeconds: Long? = null,
	val weatherSource: WeatherSource? = null,
	val lastFailureEpochSeconds: Long? = null,
	val weatherNotice: WeatherNotice? = null
)

/** Immutable scene inputs; changing the selected scene never needs to acquire weather. */
data class WeatherSceneState(
	val settings: AppSettings,
	val snapshot: WeatherSnapshot? = null,
	val snapshotTarget: WeatherSceneTarget? = null,
	val deviceLocation: GeoLocation? = null,
	val photoRevision: Int = 0,
	val locationLabel: String? = null,
	val locationLabelTarget: WeatherSceneTarget? = null,
	val snapshotNotice: WeatherNotice? = null,
	val refreshFailed: Boolean = false,
	val restoredSnapshot: Boolean = false
) {
	val simulatorActive: Boolean
		get() = sceneSimulatorOverridesWeather(settings.sceneSimulatorActive, settings.sceneSimulatorEnabled, debugToolsEnabled)

	val weatherNotice: WeatherNotice?
		get() = if (snapshot == null) {
			WeatherNotice.UNAVAILABLE
		} else if (refreshFailed || restoredSnapshot || snapshotTarget?.matchesWeather(settings, deviceLocation) != true) {
			snapshotNotice
		} else {
			null
		}
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

private data class WeatherRefreshFailure(
	val request: WeatherRequestToken,
	val target: WeatherSceneTarget,
	val epochSeconds: Long,
	val retryDelaySeconds: Long
)

internal enum class WeatherRefreshOutcome {
	REFRESHED,
	SKIPPED
}

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
	@ApplicationScope private val applicationScope: CoroutineScope,
	private val weatherCacheRepository: WeatherCacheRepository
) {
	private val _status = MutableStateFlow(WeatherSceneStatus(weatherNotice = WeatherNotice.UNAVAILABLE))
	val status: StateFlow<WeatherSceneStatus> = _status.asStateFlow()

	private val _sceneState = MutableStateFlow(WeatherSceneState(settingsRepository.defaults))
	val sceneState: StateFlow<WeatherSceneState> = _sceneState.asStateFlow()

	private val snapshot: WeatherSnapshot?
		get() = _sceneState.value.snapshot

	private val localSolarTimes = LocalSolarTimesCache()

	@Volatile private var lastRefreshEpochSeconds = 0L
	@Volatile private var lastLocationKey: String? = null
	@Volatile private var lastAttemptedWeatherProvider: WeatherProviderType? = null
	@Volatile private var lastAttemptedWeatherFallbackProvider: WeatherProviderType? = null

	private val refreshLock = Any()
	private var pendingRefreshTarget: WeatherRefreshTarget? = null
	private var pendingRefresh: Deferred<Result<WeatherRefreshOutcome>>? = null
	private val weatherRequestLock = Any()
	private var activeWeatherRequestKey: WeatherRequestKey? = null
	private var weatherRequestGeneration = 0L
	private var weatherRequestSequence = 0L
	private var lastPublishedWeatherRequestSequence = 0L
	@Volatile private var refreshFailure: WeatherRefreshFailure? = null
	private val locationLabel: String?
		get() = _sceneState.value.locationLabel

	private val deviceFixLock = Any()
	private val lastDeviceFix: GeoLocation?
		get() = _sceneState.value.deviceLocation

	@Volatile private var geocodedKey: String? = null
	private val cacheWrites = MutableStateFlow<CachedWeather?>(null)

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
				publishStatus(settings)
			}
		}

		applicationScope.launch {
			val cached = weatherCacheRepository.load()
			if (cached != null) {
				synchronized(weatherRequestLock) {
					if (snapshot == null) {
						_sceneState.update {
							it.copy(
								snapshot = cached.snapshot,
								snapshotTarget = cached.target,
								snapshotNotice = cached.notice,
								restoredSnapshot = true
							)
						}
						lastRefreshEpochSeconds = cached.refreshedAtEpochSeconds
						publishStatus(sceneState.value.settings)
					}
				}
			}

			cacheWrites.filterNotNull().collect { weatherCacheRepository.save(it) }
		}
	}

	/** The scene to draw at [nowEpochSeconds]; a clock-lit clear sky until the first weather fetch lands. */
	fun paramsFor(nowEpochSeconds: Long, state: WeatherSceneState = sceneState.value, debugToolsAvailable: Boolean = debugToolsEnabled): SceneParams = with(state.settings) {
		if (sceneSimulatorOverridesWeather(sceneSimulatorActive, sceneSimulatorEnabled, debugToolsAvailable)) {
			return@with simulatorParams(nowEpochSeconds, state)
		}

		val snapshot = state.snapshot ?: return@with fallbackParams(nowEpochSeconds, state)

		sceneParamsFor(
			weatherNotice = state.weatherNotice.takeUnless { hideStaleWeatherWarning },
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
			moonSizeScale = moonSizeScale,
			sunColorPreset = sunColorPreset,
			sunStyle = sunStyle,
			sunbeamMode = sunbeamMode,
			lensFlareEnabled = lensFlareEnabled,
			rainbowSizeScale = rainbowSizeScale,
			lensFlareMotionEnabled = lensFlareMotionEnabled,
			distantAircraftEnabled = distantAircraftEnabled,
			birdFlockSize = birdFlockSize,
			birdAppearanceInterval = birdAppearanceInterval,
			glassDropletsEnabled = glassDropletsEnabled,
			treesEnabled = treesEnabled,
			treeStyle = treeStyle,
			labelVerticalPosition = labelVerticalPosition,
			wallpaperDimmingStrength = wallpaperDimmingStrength,
			statusBarShadeStrength = statusBarShadeStrength
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

	/** The refresh result for background callers that need to distinguish provider failure, a completed refresh, and an intentional skip. */
	internal suspend fun refreshWithResult(nowEpochSeconds: Long, force: Boolean = false, resolveLocationName: Boolean = false, debugToolsAvailable: Boolean = debugToolsEnabled): Result<WeatherRefreshOutcome> {
		if (force) {
			synchronized(refreshLock) {
				pendingRefresh = null
				pendingRefreshTarget = null
			}
		}

		val settings = settingsRepository.settings.first()
		if (sceneSimulatorOverridesWeather(settings.sceneSimulatorActive, settings.sceneSimulatorEnabled, debugToolsAvailable)) {
			refreshSimulatorStatus(settings, resolveLocationName)
			return Result.success(WeatherRefreshOutcome.SKIPPED)
		}

		refreshLocationLabel(settings, resolveLocationName)

		val locationKey = locationKey(settings)
		if (weatherRefreshIsThrottled(settings, locationKey, nowEpochSeconds, force)) {
			return Result.success(WeatherRefreshOutcome.SKIPPED)
		}

		val publishedBeforeLocation = synchronized(weatherRequestLock) { lastPublishedWeatherRequestSequence }
		val location = resolveLocation(settings)
		if (location == null) {
			if (weatherRequestIsCurrent(settings)) {
				synchronized(weatherRequestLock) {
					if (lastPublishedWeatherRequestSequence == publishedBeforeLocation) {
						_sceneState.update { it.copy(refreshFailed = true) }
						publishStatus(settings)
					}
				}
			}

			logger.debug(TAG, "no location fix; keeping ${weatherFallbackDescription()}")
			return Result.success(WeatherRefreshOutcome.SKIPPED)
		}

		if (!weatherRequestIsCurrent(settings)) {
			logger.debug(TAG, "discarding obsolete weather request before location update")
			return Result.success(WeatherRefreshOutcome.SKIPPED)
		}

		// The device fix is remembered and the label refreshed again now that one exists — the first refresh has nothing cached for the pre-throttle pass to geocode.
		rememberDeviceFix(settings, location)
		refreshLocationLabel(settings, resolveLocationName)

		if (!weatherRequestIsCurrent(settings)) {
			logger.debug(TAG, "discarding obsolete weather request before fetch")
			return Result.success(WeatherRefreshOutcome.SKIPPED)
		}

		/*
		 * A provider change is an immediate-refresh trigger, not a retry policy.
		 * Consume it when the request is attempted so a failing provider does not bypass the normal interval on every visibility change.
		 */
		lastAttemptedWeatherProvider = settings.weatherProvider
		lastAttemptedWeatherFallbackProvider = settings.weatherFallbackProvider
		val request = beginWeatherRequest(settings, location)
		val requestState = sceneState.value
		val requestLocationLabel = if (!WeatherSceneTarget.selectsDeviceLocation(settings)) {
			settings.manualLocationLabel
		} else {
			requestState.locationLabel.takeIf { requestState.locationLabelTarget?.matchesLocation(settings, location) == true }
		}

		val weatherResult = weatherRepository.current(location.latitude, location.longitude)
		if (weatherResult.isFailure) {
			publishWeatherFailure(request, settings, location, nowEpochSeconds)
			return weatherResult.map { WeatherRefreshOutcome.REFRESHED }
		}

		val latestSettings = settingsRepository.settings.first()

		return weatherResult.map { weather ->
			if (!publishWeatherResponse(request, settings, latestSettings, weather, nowEpochSeconds, locationKey, location, requestLocationLabel)) {
				logger.debug(TAG, "discarding obsolete weather response")
				return@map WeatherRefreshOutcome.SKIPPED
			}

			val cloudCover = weather.observation.cloudCover
			logger.debug(TAG, "weather refreshed: provider=${weather.source.provider}, condition=${weather.observation.condition.label}, cloud=${cloudCover.totalPercent}%${cloudLayerDescription(weather)}")

			WeatherRefreshOutcome.REFRESHED
		}
	}

	private fun weatherRefreshIsThrottled(settings: AppSettings, locationKey: String, nowEpochSeconds: Long, force: Boolean): Boolean {
		val state = sceneState.value
		val failure = refreshFailure?.takeIf { it.target.matchesWeather(settings, state.deviceLocation) }
		if (failure != null) {
			return !force && nowEpochSeconds >= failure.epochSeconds && nowEpochSeconds - failure.epochSeconds < failure.retryDelaySeconds
		}

		if (state.restoredSnapshot) {
			return false
		}

		val locationChanged = locationKey != lastLocationKey || state.snapshotTarget?.matchesLocation(settings, state.deviceLocation) == false
		val weatherProviderChanged = settings.weatherProvider != lastAttemptedWeatherProvider || settings.weatherFallbackProvider != lastAttemptedWeatherFallbackProvider
		val minRefreshSeconds = settings.updateIntervalMinutes * SECONDS_PER_MINUTE

		return !force && !locationChanged && !weatherProviderChanged && nowEpochSeconds >= lastRefreshEpochSeconds && nowEpochSeconds - lastRefreshEpochSeconds < minRefreshSeconds
	}

	/** A failed target retries with bounded backoff; obsolete failures cannot replace newer success or failure state. */
	private suspend fun publishWeatherFailure(request: WeatherRequestToken, settings: AppSettings, location: GeoLocation, nowEpochSeconds: Long) {
		if (!weatherRequestIsCurrent(settings)) {
			return
		}

		synchronized(weatherRequestLock) {
			val previous = refreshFailure
			if (request.generation != weatherRequestGeneration || request.key != activeWeatherRequestKey || request.sequence <= lastPublishedWeatherRequestSequence || request.sequence <= (previous?.request?.sequence ?: 0L)) {
				return@synchronized
			}

			val target = WeatherSceneTarget.from(settings, location)
			val delay = if (previous?.target == target) {
				(previous.retryDelaySeconds * 2).coerceAtMost(MAX_RETRY_SECONDS)
			} else {
				INITIAL_RETRY_SECONDS
			}
			refreshFailure = WeatherRefreshFailure(request, target, nowEpochSeconds, delay)
			_sceneState.update { it.copy(refreshFailed = true) }
			publishStatus(settings)
		}
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
		location: GeoLocation,
		requestLocationLabel: String?
	): Boolean {
		if (!sameWeatherRequest(requestSettings, latestSettings)) {
			return false
		}

		return synchronized(weatherRequestLock) {
			if (request.generation != weatherRequestGeneration || request.key != activeWeatherRequestKey || request.sequence <= lastPublishedWeatherRequestSequence) {
				false
			} else {
				lastPublishedWeatherRequestSequence = request.sequence
				if (request.sequence > (refreshFailure?.request?.sequence ?: 0L)) {
					refreshFailure = null
				}
				val target = WeatherSceneTarget.from(requestSettings, location)
				val cached = CachedWeather(weather, target, nowEpochSeconds, requestLocationLabel)
				_sceneState.update {
					it.copy(
						snapshot = weather,
						snapshotTarget = target,
						snapshotNotice = cached.notice,
						refreshFailed = refreshFailure != null,
						restoredSnapshot = false
					)
				}
				cacheWrites.value = cached

				lastRefreshEpochSeconds = nowEpochSeconds
				lastLocationKey = locationKey
				publishStatus(requestSettings)
				true
			}
		}
	}


	/** The persisted simulator scene, carrying the same display preferences as live weather while replacing its meteorological fields. */
	private fun simulatorParams(nowEpochSeconds: Long, state: WeatherSceneState) = with(state.settings) {
		val preset = SCENE_PRESETS[sceneSimulatorPresetIndex.coerceIn(0, SCENE_PRESETS.lastIndex)]

		debugSceneParams(
			preset = preset,
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
			moonSizeScale = moonSizeScale,
			sunColorPreset = sunColorPreset,
			sunStyle = sunStyle,
			sunbeamMode = sunbeamMode,
			lensFlareEnabled = lensFlareEnabled,
			rainbowSizeScale = rainbowSizeScale,
			celestialProgress = sceneSimulatorCelestialProgress.coerceIn(0f, 1f),
			lensFlareMotionEnabled = lensFlareMotionEnabled,
			distantAircraftEnabled = distantAircraftEnabled,
			birdFlockSize = birdFlockSize,
			birdAppearanceInterval = birdAppearanceInterval,
			glassDropletsEnabled = glassDropletsEnabled,
			treesEnabled = treesEnabled,
			treeStyle = treeStyle,
			labelVerticalPosition = labelVerticalPosition,
			wallpaperDimmingStrength = wallpaperDimmingStrength,
			statusBarShadeStrength = statusBarShadeStrength
		)
			.copy(
				backdropScene = backdropScene,
				photoRevision = state.photoRevision,
				overlayLabels = overlayLabels(snapshot = null, state = state, simulatedWeather = preset.name)
			)
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
		if (!resolveLocationName && !settings.showLocationLabel) {
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
			publishStatus(settings)

			geocodedKey = null
		}
	}

	private fun publishStatus(settings: AppSettings) {
		val manual = selectedManualLocation(settings)
		val currentLocation = manual ?: lastDeviceFix
		val currentFixKey = currentLocation?.let(::locationFixKey)
		val statusLabel = manual?.let { settings.manualLocationLabel } ?: deviceLocationLabel(currentFixKey)
		val state = sceneState.value
		val lastUpdated = state.snapshotNotice?.refreshedAtEpochSeconds
		val weatherSource = state.snapshot?.source

		_status.value = WeatherSceneStatus(
			locationLabel = statusLabel,
			lastRefreshEpochSeconds = lastUpdated,
			weatherSource = weatherSource,
			lastFailureEpochSeconds = refreshFailure?.takeIf { it.target.matchesWeather(settings, lastDeviceFix) }?.epochSeconds,
			weatherNotice = state.weatherNotice
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
	private fun overlayLabels(snapshot: WeatherSnapshot?, state: WeatherSceneState, simulatedWeather: String? = null): OverlayLabels? = with(state.settings) {
		val weather = if (showWeatherLabel) {
			simulatedWeather ?: snapshot?.let { weatherText(it.observation, temperatureUnit) }
		} else {
			null
		}

		val location = if (showLocationLabel) {
			if (snapshot != null && state.snapshotTarget?.matchesLocation(state.settings, state.deviceLocation) != true) {
				state.snapshotNotice?.locationLabel
			} else {
				displayedLocationLabel(state)
			}
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

	/** Derives offline lighting from the captured location; only an unknown location needs a coarse local-clock approximation. */
	private fun fallbackParams(nowEpochSeconds: Long, state: WeatherSceneState): SceneParams = with(state.settings) {
		val usesDeviceLocation = WeatherSceneTarget.selectsDeviceLocation(state.settings)
		val latitude = if (usesDeviceLocation) state.deviceLocation?.latitude else manualLatitude
		val longitude = if (usesDeviceLocation) state.deviceLocation?.longitude else manualLongitude

		val solarTimes = if (latitude != null && longitude != null) {
			localSolarTimes.forLocation(nowEpochSeconds, latitude, longitude)
		} else {
			null
		}

		val phase = if (solarTimes != null) {
			dayPhaseFor(nowEpochSeconds, solarTimes.sunriseEpochSeconds, solarTimes.sunsetEpochSeconds, solarTimes.isDay)
		} else {
			when (Instant.ofEpochSecond(nowEpochSeconds).atZone(ZoneId.systemDefault()).hour) {
				in DAWN_HOUR until DAY_HOUR -> DayPhase.DAWN
				in DAY_HOUR until DUSK_HOUR -> DayPhase.DAY
				in DUSK_HOUR until NIGHT_HOUR -> DayPhase.DUSK
				else -> DayPhase.NIGHT
			}
		}

		SceneParams(
			weatherNotice = WeatherNotice.UNAVAILABLE.takeUnless { hideStaleWeatherWarning },
			dayPhase = phase,
			cloudiness = 0.05f,
			fogDensity = 0f,
			precipitation = null,
			thunder = false,
			windFactor = 0.2f,
			moonPhase = moonPhaseFor(nowEpochSeconds),
			celestialProgress = dayPhaseProgressFor(nowEpochSeconds, solarTimes?.sunriseEpochSeconds, solarTimes?.sunsetEpochSeconds, phase),
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
			moonSizeScale = moonSizeScale,
			sunColorPreset = sunColorPreset,
			sunStyle = sunStyle,
			sunbeamMode = sunbeamMode,
			lensFlareEnabled = lensFlareEnabled,
			rainbowSizeScale = normalizeRainbowSizeScale(rainbowSizeScale),
			lensFlareMotionEnabled = lensFlareMotionEnabled,
			distantAircraftEnabled = distantAircraftEnabled,
			birdFlockSize = birdFlockSize,
			birdAppearanceInterval = birdAppearanceInterval,
			glassDropletsEnabled = glassDropletsEnabled,
			treesEnabled = treesEnabled,
			treeStyle = treeStyle,
			labelVerticalPosition = labelVerticalPosition,
			wallpaperDimmingStrength = wallpaperDimmingStrength,
			statusBarShadeStrength = statusBarShadeStrength,
			overlayLabels = overlayLabels(snapshot = null, state = state)
		)
	}

	companion object {
		private const val TAG = "WeatherSceneProvider"
		private const val SECONDS_PER_MINUTE = 60L
		private const val INITIAL_RETRY_SECONDS = 60L
		private const val MAX_RETRY_SECONDS = 15L * SECONDS_PER_MINUTE
		private const val DAWN_HOUR = 5
		private const val DAY_HOUR = 7
		private const val DUSK_HOUR = 17
		private const val NIGHT_HOUR = 19
	}
}
