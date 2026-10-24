package xyz.attacktive.weatherd.ui.settings

import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.view.OneShotPreDrawListener
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AnimationIdleTimeout
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.domain.model.WeatherFallbackReason
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.WeatherSource
import xyz.attacktive.weatherd.domain.model.globalWeatherProviders
import xyz.attacktive.weatherd.domain.render.WeatherSceneStatus
import xyz.attacktive.weatherd.domain.model.UPDATE_INTERVAL_OPTIONS
import xyz.attacktive.weatherd.platform.HomeLauncher
import xyz.attacktive.weatherd.platform.currentHomeLauncher
import xyz.attacktive.weatherd.platform.wallpaperScrollingSupportedBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
	var selectedTab by rememberSaveable { mutableStateOf(SettingsTab.APPEARANCE) }
	var appearanceCategory by rememberSaveable { mutableStateOf<AppearanceCategory?>(null) }
	val appearanceStateHolder = rememberSaveableStateHolder()
	val weatherScrollState = rememberScrollState()
	val wallpaperScrollState = rememberScrollState()
	val advancedScrollState = rememberScrollState()
	val view = LocalView.current
	var pageReady by remember(view) { mutableStateOf(false) }
	val category = appearanceCategory
	val onBack = {
		if (category != null) {
			appearanceCategory = null
		} else {
			onNavigateBack()
		}
	}

	BackHandler(enabled = category != null) { appearanceCategory = null }

	// Wait for initial window insets before restored scroll offsets can be clamped by measurement.
	DisposableEffect(view) {
		val listener = OneShotPreDrawListener.add(view) { pageReady = true }
		onDispose { listener.removeListener() }
	}

	Scaffold { padding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
		) {
			SettingsTopBar(
				selectedTab = selectedTab,
				category = category,
				onNavigateBack = onBack,
				onSelectTab = { selectedTab = it }
			)

			Box(modifier = Modifier.weight(1f)) {
				if (pageReady) {
					when (selectedTab) {
						SettingsTab.APPEARANCE -> appearanceStateHolder.SaveableStateProvider(category?.name ?: "appearance-hub") {
							AppearanceSettingsTab(
								viewModel = viewModel,
								category = category,
								onSelectCategory = { appearanceCategory = it }
							)
						}

						SettingsTab.WEATHER -> WeatherSettingsTab(viewModel, weatherScrollState)
						SettingsTab.WALLPAPER -> WallpaperSettingsTab(viewModel, wallpaperScrollState)
						SettingsTab.ADVANCED -> AdvancedSettingsTab(viewModel, advancedScrollState)
					}
				}
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(selectedTab: SettingsTab, category: AppearanceCategory?, onNavigateBack: () -> Unit, onSelectTab: (SettingsTab) -> Unit) = Column {
	TopAppBar(
		title = {
			Column {
				Text(stringResource(category?.label ?: R.string.settings_title))

				if (category == null) {
					Text(
						text = stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
						style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant,
						maxLines = 1
					)
				}
			}
		},
		navigationIcon = {
			IconButton(onClick = onNavigateBack) {
				Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
			}
		},
		windowInsets = SETTINGS_WINDOW_INSETS
	)

	if (category == null) {
		PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal) {
			SettingsTab.entries.forEach { tab ->
				Tab(
					selected = selectedTab == tab,
					onClick = { onSelectTab(tab) },
					text = { Text(stringResource(tab.label)) }
				)
			}
		}
	}
}

@Composable
private fun WeatherSettingsTab(viewModel: SettingsViewModel, scrollState: ScrollState) {
	val settingsState = viewModel.settings.collectAsStateWithLifecycle()
	val settings = settingsState.value
	val onSave = settingsChangeCallback(settings, viewModel::applySettingsChange)
	val citySearch by viewModel.citySearch.collectAsStateWithLifecycle()
	val weatherStatus by viewModel.weatherStatus.collectAsStateWithLifecycle()
	val weatherRefreshInProgress by viewModel.weatherRefreshInProgress.collectAsStateWithLifecycle()

	SettingsTabContent(scrollState) {
		LocationSection(
			state = LocationSectionState(
				settings = settings,
				citySearch = citySearch,
				weatherStatus = weatherStatus,
				weatherRefreshInProgress = weatherRefreshInProgress
			),
			locationActions = LocationActions(
				onToggleDeviceLocation = viewModel::setUseDeviceLocation,
				onRefresh = viewModel::refreshWeather,
				onClearManualLocation = viewModel::clearManualLocation
			),
			citySearchActions = CitySearchActions(
				onQueryChange = viewModel::onCityQueryChange,
				onSearch = viewModel::searchCityImmediately,
				onClearQuery = viewModel::clearCityQuery,
				onSelectPlace = viewModel::selectPlace
			)
		)

		Spacer(modifier = Modifier.height(24.dp))

		WeatherProviderSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)

		Spacer(modifier = Modifier.height(24.dp))

		WeatherFallbackProviderSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)

		Spacer(modifier = Modifier.height(24.dp))

		RefreshIntervalSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)

		Spacer(modifier = Modifier.height(24.dp))

		WeatherComparisonSection(settings)

		if (settings.weatherProvider == WeatherProviderType.MET_NORWAY || settings.weatherFallbackProvider == WeatherProviderType.MET_NORWAY) {
			Spacer(modifier = Modifier.height(24.dp))

			MetNoAttributionSection()
		}
	}
}

@Composable
private fun WallpaperSettingsTab(viewModel: SettingsViewModel, scrollState: ScrollState) {
	val settingsState = viewModel.settings.collectAsStateWithLifecycle()
	val settings = settingsState.value
	val onSave = settingsChangeCallback(settings, viewModel::applySettingsChange)

	SettingsTabContent(scrollState) {
		WallpaperMotionSection(settings = settings, onSave = onSave)

		Spacer(modifier = Modifier.height(24.dp))

		AnimationIdleTimeoutSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)

		Spacer(modifier = Modifier.height(24.dp))

		FrameRateSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
	}
}

@Composable
private fun AdvancedSettingsTab(viewModel: SettingsViewModel, scrollState: ScrollState) {
	val settingsState = viewModel.settings.collectAsStateWithLifecycle()
	val settings = settingsState.value
	val onSave = settingsChangeCallback(settings, viewModel::applySettingsChange)

	SettingsTabContent(scrollState) {
		SceneSimulatorSection(settings = settings, onSave = onSave)

		Spacer(modifier = Modifier.height(24.dp))

		WidgetDiagnosticsSection()
	}
}

private enum class SettingsTab(@StringRes val label: Int) {
	APPEARANCE(R.string.settings_tab_appearance),
	WEATHER(R.string.settings_tab_weather),
	WALLPAPER(R.string.settings_tab_wallpaper),
	ADVANCED(R.string.settings_tab_advanced)
}

private data class LocationSectionState(
	val settings: AppSettings,
	val citySearch: CitySearchState,
	val weatherStatus: WeatherSceneStatus,
	val weatherRefreshInProgress: Boolean
)

private data class LocationActions(
	val onToggleDeviceLocation: (Boolean) -> Unit,
	val onRefresh: () -> Unit,
	val onClearManualLocation: () -> Unit
)

private data class CitySearchActions(
	val onQueryChange: (String) -> Unit,
	val onSearch: (String) -> Unit,
	val onClearQuery: () -> Unit,
	val onSelectPlace: (GeoPlace) -> Unit
)

internal fun settingsChangeCallback(settings: AppSettings, apply: (AppSettings, AppSettings) -> Unit): (AppSettings) -> Unit = { updated ->
	apply(settings, updated)
}

private data class CurrentLocationSummaryState(
	val locationLabel: String?,
	val lastRefreshEpochSeconds: Long?,
	val lastFailureEpochSeconds: Long?,
	val weatherSource: WeatherSource?,
	val refreshInProgress: Boolean,
	val onClear: (() -> Unit)?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherProviderSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_weather_provider),
		isDefault = settings.weatherProvider == defaults.weatherProvider,
		onReset = { onSave(settings.copy(weatherProvider = defaults.weatherProvider)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatWeatherProvider(settings.weatherProvider),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			WeatherProviderType.entries.forEach { provider ->
				DropdownMenuItem(
					text = { Text(formatWeatherProvider(provider)) },
					onClick = {
						if (provider != settings.weatherProvider) {
							onSave(settings.copy(weatherProvider = provider))
						}

						expanded = false
					}
				)
			}
		}
	}

	HintText(stringResource(R.string.hint_weather_provider))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherFallbackProviderSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_weather_fallback_provider),
		isDefault = settings.weatherFallbackProvider == defaults.weatherFallbackProvider,
		onReset = { onSave(settings.copy(weatherFallbackProvider = defaults.weatherFallbackProvider)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatWeatherProvider(settings.weatherFallbackProvider),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			globalWeatherProviders.forEach { provider ->
				DropdownMenuItem(
					text = { Text(formatWeatherProvider(provider)) },
					onClick = {
						if (provider != settings.weatherFallbackProvider) {
							onSave(settings.copy(weatherFallbackProvider = provider))
						}

						expanded = false
					}
				)
			}
		}
	}

	HintText(stringResource(R.string.hint_weather_fallback_provider))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIntervalSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_refresh_interval),
		isDefault = settings.updateIntervalMinutes == defaults.updateIntervalMinutes,
		onReset = { onSave(settings.copy(updateIntervalMinutes = defaults.updateIntervalMinutes)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatInterval(settings.updateIntervalMinutes),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			UPDATE_INTERVAL_OPTIONS.forEach { minutes ->
				DropdownMenuItem(
					text = { Text(formatInterval(minutes)) },
					onClick = {
						if (minutes != settings.updateIntervalMinutes) {
							onSave(settings.copy(updateIntervalMinutes = minutes))
						}

						expanded = false
					}
				)
			}
		}
	}

	HintText(stringResource(R.string.hint_refresh_interval))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnimationIdleTimeoutSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_animation_idle_timeout),
		isDefault = settings.animationIdleTimeout == defaults.animationIdleTimeout,
		onReset = { onSave(settings.copy(animationIdleTimeout = defaults.animationIdleTimeout)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatAnimationIdleTimeout(settings.animationIdleTimeout),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			AnimationIdleTimeout.entries.forEach { timeout ->
				DropdownMenuItem(
					text = { Text(formatAnimationIdleTimeout(timeout)) },
					onClick = {
						if (timeout != settings.animationIdleTimeout) {
							onSave(settings.copy(animationIdleTimeout = timeout))
						}

						expanded = false
					}
				)
			}
		}
	}

	HintText(stringResource(R.string.hint_animation_idle_timeout))
}

@Composable
private fun formatAnimationIdleTimeout(timeout: AnimationIdleTimeout) = when (timeout) {
	AnimationIdleTimeout.NEVER -> stringResource(R.string.animation_idle_timeout_never)
	AnimationIdleTimeout.SECONDS_15 -> stringResource(R.string.animation_idle_timeout_15_seconds)
	AnimationIdleTimeout.SECONDS_30 -> stringResource(R.string.animation_idle_timeout_30_seconds)
	AnimationIdleTimeout.MINUTE_1 -> stringResource(R.string.animation_idle_timeout_1_minute)
	AnimationIdleTimeout.MINUTES_2 -> stringResource(R.string.animation_idle_timeout_2_minutes)
	AnimationIdleTimeout.MINUTES_5 -> stringResource(R.string.animation_idle_timeout_5_minutes)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FrameRateSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_frame_rate),
		isDefault = settings.frameRateCap == defaults.frameRateCap,
		onReset = { onSave(settings.copy(frameRateCap = defaults.frameRateCap)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatFrameRate(settings.frameRateCap),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			FrameRateCap.entries.forEach { cap ->
				DropdownMenuItem(
					text = { Text(formatFrameRate(cap)) },
					onClick = {
						if (cap != settings.frameRateCap) {
							onSave(settings.copy(frameRateCap = cap))
						}

						expanded = false
					}
				)
			}
		}
	}

	HintText(stringResource(R.string.hint_frame_rate))
}

@Composable
private fun WallpaperMotionSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	val homeLauncher = rememberCurrentHomeLauncher()
	val scrollingSupported = wallpaperScrollingSupportedBy(homeLauncher?.packageName)
	val subtitle = if (scrollingSupported) {
		stringResource(R.string.subtitle_wallpaper_scrolling)
	} else {
		stringResource(R.string.subtitle_wallpaper_scrolling_unsupported, homeLauncher?.label.orEmpty())
	}

	SectionLabel(stringResource(R.string.section_wallpaper_motion))

	ToggleSetting(
		label = stringResource(R.string.label_wallpaper_scrolling),
		subtitle = subtitle,
		checked = settings.wallpaperScrollingEnabled && scrollingSupported,
		onToggle = { onSave(settings.copy(wallpaperScrollingEnabled = it)) },
		enabled = scrollingSupported,
	)
}

@Composable
private fun rememberCurrentHomeLauncher(): HomeLauncher? {
	val context = LocalContext.current
	val lifecycleOwner = LocalLifecycleOwner.current
	var launcher by remember(context) { mutableStateOf(currentHomeLauncher(context)) }

	DisposableEffect(context, lifecycleOwner) {
		val observer = LifecycleEventObserver { _, event ->
			if (event == Lifecycle.Event.ON_RESUME) {
				launcher = currentHomeLauncher(context)
			}
		}

		lifecycleOwner.lifecycle.addObserver(observer)

		onDispose {
			lifecycleOwner.lifecycle.removeObserver(observer)
		}
	}

	return launcher
}

@Composable
private fun SceneSimulatorSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	SectionLabel(stringResource(R.string.section_preview_tools))

	ToggleSetting(
		label = stringResource(R.string.label_scene_simulator),
		subtitle = stringResource(R.string.subtitle_scene_simulator),
		checked = settings.sceneSimulatorEnabled,
		onToggle = {
			onSave(settings.copy(sceneSimulatorEnabled = it, sceneSimulatorActive = settings.sceneSimulatorActive && it))
		}
	)
}

@Composable
private fun MetNoAttributionSection() {
	val uriHandler = LocalUriHandler.current

	SectionLabel(stringResource(R.string.section_data_attribution))

	TextButton(onClick = { uriHandler.openUri(MET_NORWAY_URL) }) {
		Text(stringResource(R.string.attribution_met_norway))
	}

	TextButton(onClick = { uriHandler.openUri(CC_BY_4_URL) }) {
		Text(stringResource(R.string.attribution_met_norway_license))
	}
}

@Composable
private fun LocationSection(
	state: LocationSectionState,
	locationActions: LocationActions,
	citySearchActions: CitySearchActions
) {
	var query by rememberSaveable { mutableStateOf("") }
	val settings = state.settings
	val manualLocationSelected = !settings.useDeviceLocation && settings.manualLatitude != null && settings.manualLongitude != null
	val summaryState = CurrentLocationSummaryState(
		locationLabel = if (manualLocationSelected) settings.manualLocationLabel else state.weatherStatus.locationLabel,
		lastRefreshEpochSeconds = state.weatherStatus.lastRefreshEpochSeconds,
		lastFailureEpochSeconds = state.weatherStatus.lastFailureEpochSeconds,
		weatherSource = state.weatherStatus.weatherSource,
		refreshInProgress = state.weatherRefreshInProgress,
		onClear = locationActions.onClearManualLocation.takeIf { manualLocationSelected }
	)

	SectionLabel(stringResource(R.string.section_location))

	ToggleSetting(
		label = stringResource(R.string.label_use_device_location),
		subtitle = stringResource(R.string.subtitle_use_device_location),
		checked = settings.useDeviceLocation,
		onToggle = locationActions.onToggleDeviceLocation,
	)

	Spacer(modifier = Modifier.height(8.dp))

	CurrentLocationSummary(state = summaryState, onRefresh = locationActions.onRefresh)

	AnimatedVisibility(visible = !settings.useDeviceLocation) {
		Column {
			Spacer(modifier = Modifier.height(8.dp))

			CitySearchField(
				query = query,
				onQueryChange = {
					query = it
					citySearchActions.onQueryChange(it)
				},
				onSearch = citySearchActions.onSearch,
				onClear = {
					query = ""
					citySearchActions.onClearQuery()
				}
			)

			CitySearchResults(
				state = state.citySearch,
				onSelectPlace = { place ->
					query = ""
					citySearchActions.onSelectPlace(place)
				}
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitySearchField(
	query: String,
	onQueryChange: (String) -> Unit,
	onSearch: (String) -> Unit,
	onClear: () -> Unit
) {
	OutlinedTextField(
		value = query,
		onValueChange = onQueryChange,
		label = { Text(stringResource(R.string.label_city)) },
		placeholder = { Text(stringResource(R.string.placeholder_city)) },
		singleLine = true,
		trailingIcon = {
			Row(verticalAlignment = Alignment.CenterVertically) {
				if (query.isNotEmpty()) {
					IconButton(onClick = onClear) {
						Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.content_description_clear_city))
					}
				}

				IconButton(onClick = { onSearch(query) }) {
					Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search))
				}
			}
		},
		keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
		keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
		modifier = Modifier.fillMaxWidth()
	)
}

@Composable
private fun CitySearchResults(state: CitySearchState, onSelectPlace: (GeoPlace) -> Unit) {
	when (state) {
		CitySearchState.Idle -> Unit

		CitySearchState.Loading -> Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(vertical = 16.dp),
			horizontalArrangement = Arrangement.Center
		) {
			CircularProgressIndicator()
		}

		CitySearchState.Empty -> HintText(stringResource(R.string.city_search_empty))

		is CitySearchState.Error -> HintText(state.message)

		is CitySearchState.Results -> Column {
			state.places.forEach { place ->
				HorizontalDivider()

				Text(
					text = place.label,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onSelectPlace(place) }
						.padding(vertical = 12.dp)
				)
			}
		}
	}
}

@Composable
private fun CurrentLocationSummary(state: CurrentLocationSummaryState, onRefresh: () -> Unit) {
	val lastUpdated = formatLastUpdated(state.lastRefreshEpochSeconds)
	val weatherSource = state.weatherSource?.let { formatWeatherSource(it) }

	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(modifier = Modifier.weight(1f)) {
			SectionLabel(stringResource(R.string.section_current_location))
			Text(state.locationLabel ?: stringResource(R.string.location_unavailable))
			Text(
				lastUpdated,
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant
			)

			if (state.lastFailureEpochSeconds != null) {
				Text(
					stringResource(R.string.weather_refresh_failed),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.error
				)
			}

			if (weatherSource != null) {
				Text(
					weatherSource,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}
		}

		state.onClear?.let { onClear ->
			IconButton(onClick = onClear) {
				Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.content_description_clear_city))
			}
		}
	}

	TextButton(onClick = onRefresh, enabled = !state.refreshInProgress) {
		if (state.refreshInProgress) {
			CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
			Spacer(modifier = Modifier.width(8.dp))
		}

		Text(stringResource(R.string.refresh_now))
	}

	HintText(stringResource(R.string.hint_weather_refresh_widget))
}

@Composable
private fun formatLastUpdated(lastRefreshEpochSeconds: Long?): String {
	if (lastRefreshEpochSeconds == null) {
		return stringResource(R.string.last_updated_never)
	}

	val context = LocalContext.current
	val formatted = DateUtils.formatDateTime(
		context,
		lastRefreshEpochSeconds * 1000L,
		DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH
	)

	return stringResource(R.string.last_updated, formatted)
}

@Composable
private fun formatWeatherProvider(provider: WeatherProviderType) = when (provider) {
	WeatherProviderType.OPEN_METEO -> stringResource(R.string.provider_open_meteo)
	WeatherProviderType.MET_NORWAY -> stringResource(R.string.provider_met_norway)
	WeatherProviderType.DWD_ICON_GLOBAL -> stringResource(R.string.provider_dwd_icon_global)
	WeatherProviderType.DWD_ICON_EU -> stringResource(R.string.provider_dwd_icon_eu)
	WeatherProviderType.DWD_ICON_D2 -> stringResource(R.string.provider_dwd_icon_d2)
	WeatherProviderType.ITALIA_METEO -> stringResource(R.string.provider_italia_meteo)
	WeatherProviderType.METEOSWISS_ICON_CH1 -> stringResource(R.string.provider_meteoswiss_icon_ch1)
	WeatherProviderType.METEOSWISS_ICON_CH2 -> stringResource(R.string.provider_meteoswiss_icon_ch2)
}

@Composable
private fun formatWeatherSource(source: WeatherSource): String {
	val provider = formatWeatherProvider(source.provider)

	return when (source.fallbackReason) {
		null -> stringResource(R.string.weather_source, provider)
		WeatherFallbackReason.OUTSIDE_COVERAGE -> stringResource(R.string.weather_source_fallback_outside_coverage, provider)
		WeatherFallbackReason.PRIMARY_FAILED -> stringResource(R.string.weather_source_fallback_primary_failed, provider)
	}
}

@Composable
private fun formatInterval(minutes: Int) = when {
	minutes < 60 -> stringResource(R.string.interval_minutes, minutes)
	minutes == 60 -> stringResource(R.string.interval_one_hour)
	minutes % 60 == 0 -> stringResource(R.string.interval_hours, minutes / 60)
	else -> stringResource(R.string.interval_minutes, minutes)
}

@Composable
private fun formatFrameRate(cap: FrameRateCap) = when (cap) {
	FrameRateCap.UNCAPPED -> stringResource(R.string.frame_rate_uncapped)
	FrameRateCap.FPS_30 -> stringResource(R.string.frame_rate_30)
	FrameRateCap.FPS_15 -> stringResource(R.string.frame_rate_15)
	FrameRateCap.FPS_10 -> stringResource(R.string.frame_rate_10)
}

private const val MET_NORWAY_URL = "https://api.met.no"
private const val CC_BY_4_URL = "https://creativecommons.org/licenses/by/4.0/"

private val SETTINGS_WINDOW_INSETS = WindowInsets(0, 0, 0, 0)
