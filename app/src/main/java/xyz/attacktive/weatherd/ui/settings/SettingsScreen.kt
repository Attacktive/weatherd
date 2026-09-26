package xyz.attacktive.weatherd.ui.settings

import kotlin.math.roundToInt
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.StringRes
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Image
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_COUNT_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.domain.model.INTENSITY_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.NIGHT_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_SATURATION_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.domain.model.SUN_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.WeatherProviderType
import xyz.attacktive.weatherd.domain.model.UPDATE_INTERVAL_OPTIONS
import xyz.attacktive.weatherd.domain.model.drawsScenery
import xyz.attacktive.weatherd.platform.HomeLauncher
import xyz.attacktive.weatherd.platform.currentHomeLauncher
import xyz.attacktive.weatherd.platform.wallpaperScrollingSupportedBy

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
	val settings by viewModel.settings.collectAsStateWithLifecycle()
	val defaults = viewModel.defaults
	val citySearch by viewModel.citySearch.collectAsStateWithLifecycle()
	val photoBuckets by viewModel.photoBuckets.collectAsStateWithLifecycle()
	val photoThumbnails by viewModel.photoThumbnails.collectAsStateWithLifecycle()
	val photoImportFailed by viewModel.photoImportFailed.collectAsStateWithLifecycle()
	var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
	val weatherScrollState = rememberScrollState()
	val appearanceScrollState = rememberScrollState()
	val wallpaperScrollState = rememberScrollState()
	val advancedScrollState = rememberScrollState()

	Scaffold(
		topBar = {
			Column {
				TopAppBar(
					title = { Text(stringResource(R.string.settings_title)) },
					navigationIcon = {
						IconButton(onClick = onNavigateBack) {
							Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
						}
					}
				)

				PrimaryScrollableTabRow(selectedTabIndex = selectedTabIndex) {
					SettingsTab.entries.forEachIndexed { index, tab ->
						Tab(
							selected = selectedTabIndex == index,
							onClick = { selectedTabIndex = index },
							text = { Text(stringResource(tab.label)) }
						)
					}
				}
			}
		}
	) { padding ->
		Box(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
		) {
			when (SettingsTab.entries[selectedTabIndex]) {
				SettingsTab.WEATHER -> SettingsTabContent(weatherScrollState) {
					LocationSection(
						settings = settings,
						citySearch = citySearch,
						onToggleDeviceLocation = { viewModel.save(settings.copy(useDeviceLocation = it)) },
						onQueryChange = viewModel::onCityQueryChange,
						onSearch = viewModel::searchCityImmediately,
						onClearQuery = viewModel::clearCityQuery,
						onSelectPlace = viewModel::selectPlace,
						onClearManualLocation = viewModel::clearManualLocation
					)

					Spacer(modifier = Modifier.height(24.dp))

					WeatherProviderSection(settings = settings, defaults = defaults, onSave = viewModel::save)

					Spacer(modifier = Modifier.height(24.dp))

					RefreshIntervalSection(settings = settings, defaults = defaults, onSave = viewModel::save)
				}

				SettingsTab.APPEARANCE -> SettingsTabContent(appearanceScrollState) {
					BackdropSection(settings = settings, defaults = defaults, onSave = viewModel::save)

					AnimatedVisibility(visible = settings.backdropScene == BackdropScene.PHOTO) {
						Column {
							Spacer(modifier = Modifier.height(24.dp))

							PhotoBackgroundSection(
								buckets = photoBuckets,
								thumbnails = photoThumbnails,
								importFailed = photoImportFailed,
								onChoose = viewModel::importPhoto,
								onClear = viewModel::clearPhoto,
								onDismissFailure = viewModel::dismissImportFailure
							)
						}
					}

					Spacer(modifier = Modifier.height(24.dp))

					SkyAppearanceSection(settings = settings, defaults = defaults, onSave = viewModel::save)

					Spacer(modifier = Modifier.height(24.dp))

					CloudAppearanceSection(settings = settings, defaults = defaults, onSave = viewModel::save)

					Spacer(modifier = Modifier.height(24.dp))

					SunEffectsSection(settings = settings, defaults = defaults, onSave = viewModel::save)

					Spacer(modifier = Modifier.height(24.dp))

					IntensitySection(settings = settings, defaults = defaults, onSave = viewModel::save)

					Spacer(modifier = Modifier.height(24.dp))

					LabelsSection(settings = settings, defaults = defaults, onSave = viewModel::save)
				}

				SettingsTab.WALLPAPER -> SettingsTabContent(wallpaperScrollState) {
					WallpaperMotionSection(settings = settings, onSave = viewModel::save)

					Spacer(modifier = Modifier.height(24.dp))

					FrameRateSection(settings = settings, defaults = defaults, onSave = viewModel::save)
				}

				SettingsTab.ADVANCED -> SettingsTabContent(advancedScrollState) {
					SceneSimulatorSection(settings = settings, onSave = viewModel::save)

					if (settings.weatherProvider == WeatherProviderType.MET_NORWAY) {
						Spacer(modifier = Modifier.height(24.dp))

						MetNoAttributionSection()
					}

					Spacer(modifier = Modifier.height(24.dp))

					VersionFooter()
				}
			}
		}
	}
}

private enum class SettingsTab(@StringRes val label: Int) {
	WEATHER(R.string.settings_tab_weather),
	APPEARANCE(R.string.settings_tab_appearance),
	WALLPAPER(R.string.settings_tab_wallpaper),
	ADVANCED(R.string.settings_tab_advanced)
}

private val BACKDROP_SCENE_DISPLAY_ORDER = listOf(
	BackdropScene.NONE,
	BackdropScene.PHOTO,
	BackdropScene.METROPOLIS,
	BackdropScene.BEACH,
	BackdropScene.MOUNTAINS,
	BackdropScene.COUNTRYSIDE
)

@Composable
private fun SettingsTabContent(scrollState: ScrollState, content: @Composable ColumnScope.() -> Unit) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.verticalScroll(scrollState)
			.padding(16.dp),
		content = content
	)
}

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
private fun IntensitySection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	IntensitySlider(
		label = stringResource(R.string.section_precipitation_intensity),
		value = settings.precipitationIntensityScale,
		defaultValue = defaults.precipitationIntensityScale,
		onCommit = { onSave(settings.copy(precipitationIntensityScale = it)) }
	)

	IntensitySlider(
		label = stringResource(R.string.section_wind_intensity),
		value = settings.windIntensityScale,
		defaultValue = defaults.windIntensityScale,
		onCommit = { onSave(settings.copy(windIntensityScale = it)) }
	)

	IntensitySlider(
		label = stringResource(R.string.section_cloud_opacity),
		value = settings.cloudIntensityScale,
		defaultValue = defaults.cloudIntensityScale,
		onCommit = { onSave(settings.copy(cloudIntensityScale = it)) }
	)

	HintText(stringResource(R.string.hint_intensity))
}

@Composable
private fun SkyAppearanceSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	SkyColorPicker(settings = settings, defaults = defaults, onSave = onSave)

	Spacer(modifier = Modifier.height(12.dp))

	PercentageSlider(
		label = R.string.section_sky_brightness,
		value = settings.skyBrightnessScale,
		defaultValue = defaults.skyBrightnessScale,
		valueRange = SKY_BRIGHTNESS_SCALE_RANGE,
		lowLabel = R.string.sky_brightness_darker,
		highLabel = R.string.sky_brightness_brighter,
		onCommit = { onSave(settings.copy(skyBrightnessScale = it)) }
	)

	Spacer(modifier = Modifier.height(12.dp))

	PercentageSlider(
		label = R.string.section_night_brightness,
		value = settings.nightBrightnessScale,
		defaultValue = defaults.nightBrightnessScale,
		valueRange = NIGHT_BRIGHTNESS_SCALE_RANGE,
		lowLabel = R.string.night_brightness_black,
		highLabel = R.string.night_brightness_normal,
		onCommit = { onSave(settings.copy(nightBrightnessScale = it)) }
	)

	Spacer(modifier = Modifier.height(12.dp))

	PercentageSlider(
		label = R.string.section_sky_saturation,
		value = settings.skySaturationScale,
		defaultValue = defaults.skySaturationScale,
		valueRange = SKY_SATURATION_SCALE_RANGE,
		lowLabel = R.string.sky_saturation_muted,
		highLabel = R.string.sky_saturation_vivid,
		onCommit = { onSave(settings.copy(skySaturationScale = it)) }
	)

	HintText(stringResource(R.string.hint_sky_appearance))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkyColorPicker(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_sky_palette),
		isDefault = settings.skyColorPreset == defaults.skyColorPreset,
		onReset = { onSave(settings.copy(skyColorPreset = defaults.skyColorPreset)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatSkyColor(settings.skyColorPreset),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			SkyColorPreset.entries.forEach { preset ->
				DropdownMenuItem(
					text = { Text(formatSkyColor(preset)) },
					onClick = {
						if (preset != settings.skyColorPreset) {
							onSave(settings.copy(skyColorPreset = preset))
						}

						expanded = false
					}
				)
			}
		}
	}
}

@Composable
private fun CloudAppearanceSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	PercentageSlider(
		label = R.string.section_cloud_size,
		value = settings.cloudSizeScale,
		defaultValue = defaults.cloudSizeScale,
		valueRange = CLOUD_SIZE_SCALE_RANGE,
		lowLabel = R.string.cloud_size_small,
		highLabel = R.string.cloud_size_large,
		onCommit = { onSave(settings.copy(cloudSizeScale = it)) }
	)

	PercentageSlider(
		label = R.string.section_cloud_count,
		value = settings.cloudCountScale,
		defaultValue = defaults.cloudCountScale,
		valueRange = CLOUD_COUNT_SCALE_RANGE,
		lowLabel = R.string.cloud_count_fewer,
		highLabel = R.string.cloud_count_more,
		onCommit = { onSave(settings.copy(cloudCountScale = it)) }
	)

	HintText(stringResource(R.string.hint_cloud_composition))

	Spacer(modifier = Modifier.height(12.dp))

	PercentageSlider(
		label = R.string.section_cloud_contrast,
		value = settings.cloudContrastScale,
		defaultValue = defaults.cloudContrastScale,
		valueRange = CLOUD_CONTRAST_SCALE_RANGE,
		lowLabel = R.string.cloud_contrast_softer,
		highLabel = R.string.cloud_contrast_stronger,
		onCommit = { onSave(settings.copy(cloudContrastScale = it)) }
	)

	HintText(stringResource(R.string.hint_cloud_contrast))
}

@Composable
private fun PercentageSlider(label: Int, value: Float, defaultValue: Float, valueRange: ClosedFloatingPointRange<Float>, lowLabel: Int, highLabel: Int, onCommit: (Float) -> Unit) {
	var position by remember(value) { mutableFloatStateOf(value) }
	val labelText = stringResource(label, (position * 100f).roundToInt())

	ResettableSectionLabel(
		text = labelText,
		isDefault = position == defaultValue,
		onReset = {
			position = defaultValue
			onCommit(defaultValue)
		}
	)

	Slider(
		value = position,
		onValueChange = { position = it },
		onValueChangeFinished = { onCommit(position) },
		valueRange = valueRange
	)

	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		HintText(stringResource(lowLabel))
		HintText(stringResource(highLabel))
	}
}

/**
 * A labeled multiplier slider that shows the current percentage above qualitative endpoints.
 * The drag position is local state and only commits on release: a DataStore write per pixel would hammer the settings file and restart the scene mid-gesture.
 */
@Composable
private fun IntensitySlider(label: String, value: Float, defaultValue: Float, onCommit: (Float) -> Unit) {
	var position by remember(value) { mutableFloatStateOf(value) }
	val labelText = stringResource(R.string.setting_with_percentage, label, (position * 100f).roundToInt())

	ResettableSectionLabel(
		text = labelText,
		isDefault = position == defaultValue,
		onReset = {
			position = defaultValue
			onCommit(defaultValue)
		}
	)

	Slider(
		value = position,
		onValueChange = { position = it },
		onValueChangeFinished = { onCommit(position) },
		valueRange = INTENSITY_SCALE_RANGE
	)

	Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		HintText(stringResource(R.string.intensity_subtle))
		HintText(stringResource(R.string.intensity_intense))
	}
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
private fun SunEffectsSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	SectionLabel(stringResource(R.string.section_sun_effects))

	ToggleSetting(
		label = stringResource(R.string.label_show_sun),
		subtitle = stringResource(R.string.subtitle_show_sun),
		checked = settings.sunVisible,
		onToggle = { onSave(settings.copy(sunVisible = it)) },
	)

	ToggleSetting(
		label = stringResource(R.string.label_show_moon),
		subtitle = stringResource(R.string.subtitle_show_moon),
		checked = settings.moonVisible,
		onToggle = { onSave(settings.copy(moonVisible = it)) },
	)

	AnimatedVisibility(visible = settings.sunVisible) {
		Column {
			Spacer(modifier = Modifier.height(12.dp))
			SunSizeSlider(settings = settings, defaults = defaults, onSave = onSave)
			Spacer(modifier = Modifier.height(12.dp))
			SunColorPicker(settings = settings, defaults = defaults, onSave = onSave)
			Spacer(modifier = Modifier.height(12.dp))

			ToggleSetting(
				label = stringResource(R.string.label_lens_flare),
				subtitle = stringResource(R.string.subtitle_lens_flare),
				checked = settings.lensFlareEnabled,
				onToggle = { onSave(settings.copy(lensFlareEnabled = it)) },
			)
		}
	}
}

@Composable
private fun SunSizeSlider(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	PercentageSlider(
		label = R.string.label_sun_size,
		value = settings.sunSizeScale,
		defaultValue = defaults.sunSizeScale,
		valueRange = SUN_SIZE_SCALE_RANGE,
		lowLabel = R.string.sun_size_small,
		highLabel = R.string.sun_size_large,
		onCommit = { onSave(settings.copy(sunSizeScale = it)) }
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SunColorPicker(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.label_sun_color),
		isDefault = settings.sunColorPreset == defaults.sunColorPreset,
		onReset = { onSave(settings.copy(sunColorPreset = defaults.sunColorPreset)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatSunColor(settings.sunColorPreset),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			SunColorPreset.entries.forEach { preset ->
				DropdownMenuItem(
					text = { Text(formatSunColor(preset)) },
					onClick = {
						if (preset != settings.sunColorPreset) {
							onSave(settings.copy(sunColorPreset = preset))
						}

						expanded = false
					}
				)
			}
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackdropSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_backdrop),
		isDefault = settings.backdropScene == defaults.backdropScene,
		onReset = { onSave(settings.copy(backdropScene = defaults.backdropScene)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatBackdrop(settings.backdropScene),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			BACKDROP_SCENE_DISPLAY_ORDER.forEach { scene ->
				DropdownMenuItem(
					text = { Text(formatBackdrop(scene)) },
					onClick = {
						if (scene != settings.backdropScene) {
							onSave(settings.copy(backdropScene = scene))
						}

						expanded = false
					}
				)
			}
		}
	}
}

/**
 * The four photo slots, shown only while [BackdropScene.PHOTO] is the chosen backdrop.
 * [buckets] comes straight from the repository rather than from anything this screen remembers, so a row can only claim a photo the store actually holds.
 * [thumbnails] carries downsampled preview bitmaps for each filled slot, decoded off the main thread.
 */
@Composable
private fun PhotoBackgroundSection(
	buckets: Set<PhotoBucket>,
	thumbnails: Map<PhotoBucket, Bitmap>,
	importFailed: Boolean,
	onChoose: (PhotoBucket, Uri) -> Unit,
	onClear: (PhotoBucket) -> Unit,
	onDismissFailure: () -> Unit
) {
	// The failure belongs to the pick that caused it, so it dies with the section: switching the backdrop away and back must not bring back an error with nothing the user just did behind it.
	DisposableEffect(Unit) {
		onDispose(onDismissFailure)
	}

	SectionLabel(stringResource(R.string.section_photo_backgrounds))

	PhotoBucket.entries.forEach { bucket ->
		PhotoBucketRow(
			bucket = bucket,
			isSet = bucket in buckets,
			thumbnail = thumbnails[bucket],
			onChoose = { onChoose(bucket, it) },
			onClear = { onClear(bucket) }
		)
	}

	if (importFailed) {
		ErrorText(stringResource(R.string.photo_import_failed))
	}

	HintText(stringResource(R.string.hint_photo_backgrounds))

	// Dawn and dusk only ever borrow from day and night, so a set holding neither is one where the painted sky still covers every hour the user is awake for.
	if (PhotoBucket.DAY !in buckets && PhotoBucket.NIGHT !in buckets) {
		HintText(stringResource(R.string.hint_photo_incomplete))
	}
}

/**
 * One photo slot: what it covers, whether it holds a photo, its thumbnail preview, and the picker and clear buttons for it.
 * The launcher belongs to the row rather than to the section so the picked [Uri] arrives already knowing which bucket asked for it, with no pending-bucket state to lose to a process death mid-pick.
 */
@Composable
private fun PhotoBucketRow(bucket: PhotoBucket, isSet: Boolean, thumbnail: Bitmap?, onChoose: (Uri) -> Unit, onClear: () -> Unit) {
	// The chooser contract is what forces the system to show every registered handler — including Wallhavend — instead of routing ACTION_GET_CONTENT straight to the default photo app.
	val contract = remember { ChoosableGetContent() }
	val picker = rememberLauncherForActivityResult(contract) { picked ->
		// Null is the user backing out of the picker, which is not a failure and must not be reported as one.
		if (picked != null) {
			onChoose(picked)
		}
	}

	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		PhotoBucketPreview(thumbnail)

		Spacer(modifier = Modifier.width(16.dp))

		Column(modifier = Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(formatPhotoBucket(bucket))

				// A bucket with a parent has somewhere to fall back to, which is exactly what makes it optional.
				if (bucket.parent != null) {
					Spacer(modifier = Modifier.width(8.dp))

					Text(stringResource(R.string.photo_optional), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}

			Text(formatPhotoState(isSet), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}

		TextButton(onClick = { picker.launch("image/*") }) {
			Text(formatPhotoAction(isSet))
		}

		if (isSet) {
			// Four rows carry the same icon, so the description names the bucket it clears; a "Day, Clear" enumeration rather than a sentence, which keeps it out of the way of word order in the ten locales the strings are translated into.
			val clearDescription = "${formatPhotoBucket(bucket)}, ${stringResource(R.string.photo_clear)}"

			IconButton(onClick = onClear) {
				Icon(Icons.Filled.Clear, contentDescription = clearDescription)
			}
		}
	}
}

/** The decorative preview for one photo slot; the labeled Choose or Replace button owns picker access. */
@Composable
private fun PhotoBucketPreview(thumbnail: Bitmap?) {
	if (thumbnail != null) {
		Image(
			bitmap = thumbnail.asImageBitmap(),
			contentDescription = null,
			contentScale = ContentScale.Crop,
			modifier = Modifier
				.size(48.dp)
				.clip(MaterialTheme.shapes.small)
		)
	} else {
		Box(
			modifier = Modifier
				.size(48.dp)
				.clip(MaterialTheme.shapes.small)
				.background(MaterialTheme.colorScheme.surfaceVariant),
			contentAlignment = Alignment.Center
		) {
			Icon(
				imageVector = Icons.Outlined.Image,
				contentDescription = null,
				tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
				modifier = Modifier.size(24.dp)
			)
		}
	}
}

@Composable
private fun LabelsSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	SectionLabel(stringResource(R.string.section_labels))

	ToggleSetting(
		label = stringResource(R.string.label_show_weather),
		subtitle = stringResource(R.string.subtitle_show_weather),
		checked = settings.showWeatherLabel,
		onToggle = { onSave(settings.copy(showWeatherLabel = it)) },
	)

	AnimatedVisibility(visible = settings.showWeatherLabel) {
		Column {
			Spacer(modifier = Modifier.height(8.dp))

			ResettableSectionLabel(
				text = stringResource(R.string.section_temperature_unit),
				isDefault = settings.temperatureUnit == defaults.temperatureUnit,
				onReset = { onSave(settings.copy(temperatureUnit = defaults.temperatureUnit)) }
			)

			SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
				TemperatureUnit.entries.forEachIndexed { index, unit ->
					SegmentedButton(
						selected = settings.temperatureUnit == unit,
						onClick = {
							if (unit != settings.temperatureUnit) {
								onSave(settings.copy(temperatureUnit = unit))
							}
						},
						shape = SegmentedButtonDefaults.itemShape(index = index, count = TemperatureUnit.entries.size)
					) {
						Text(formatUnit(unit))
					}
				}
			}

			Spacer(modifier = Modifier.height(4.dp))
		}
	}

	ToggleSetting(
		label = stringResource(R.string.label_show_location),
		subtitle = stringResource(R.string.subtitle_show_location),
		checked = settings.showLocationLabel,
		onToggle = { onSave(settings.copy(showLocationLabel = it)) },
	)
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
	settings: AppSettings,
	citySearch: CitySearchState,
	onToggleDeviceLocation: (Boolean) -> Unit,
	onQueryChange: (String) -> Unit,
	onSearch: (String) -> Unit,
	onClearQuery: () -> Unit,
	onSelectPlace: (GeoPlace) -> Unit,
	onClearManualLocation: () -> Unit
) {
	var query by rememberSaveable { mutableStateOf("") }

	SectionLabel(stringResource(R.string.section_location))

	ToggleSetting(
		label = stringResource(R.string.label_use_device_location),
		subtitle = stringResource(R.string.subtitle_use_device_location),
		checked = settings.useDeviceLocation,
		onToggle = onToggleDeviceLocation,
	)

	AnimatedVisibility(visible = !settings.useDeviceLocation) {
		Column {
			Spacer(modifier = Modifier.height(8.dp))

			settings.manualLocationLabel?.let { label ->
				CurrentManualLocation(label = label, onClear = onClearManualLocation)
				Spacer(modifier = Modifier.height(8.dp))
			}

			CitySearchField(
				query = query,
				onQueryChange = {
					query = it
					onQueryChange(it)
				},
				onSearch = onSearch,
				onClear = {
					query = ""
					onClearQuery()
				}
			)

			CitySearchResults(
				state = citySearch,
				onSelectPlace = { place ->
					query = ""
					onSelectPlace(place)
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
private fun CurrentManualLocation(label: String, onClear: () -> Unit) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(modifier = Modifier.weight(1f)) {
			SectionLabel(stringResource(R.string.section_current_city))
			Text(label)
		}

		IconButton(onClick = onClear) {
			Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.content_description_clear_city))
		}
	}
}

@Composable
private fun ToggleSetting(label: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit, enabled: Boolean = true) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.alpha(if (enabled) 1f else 0.5f),
		verticalAlignment = Alignment.CenterVertically
	) {
		Column(modifier = Modifier.weight(1f)) {
			Text(label)
			Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}

		Switch(checked = checked, onCheckedChange = onToggle, enabled = enabled)
	}
}

@Composable
private fun VersionFooter() {
	Text(
		text = stringResource(R.string.version_footer, BuildConfig.VERSION_NAME),
		style = MaterialTheme.typography.bodyMedium,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		textAlign = TextAlign.Center,
		modifier = Modifier
			.fillMaxWidth()
			.padding(16.dp)
	)
}

@Composable
private fun ResettableSectionLabel(text: String, isDefault: Boolean, onReset: () -> Unit) {
	Row(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
		Text(
			text = text,
			style = MaterialTheme.typography.labelSmall,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
			modifier = Modifier
				.weight(1f)
				.padding(bottom = 4.dp)
		)

		if (!isDefault) {
			ResetButton(settingLabel = text, onClick = onReset)
		}
	}
}

@Composable
private fun ResetButton(settingLabel: String, onClick: () -> Unit) {
	val resetDescription = stringResource(R.string.content_description_reset_to_default, settingLabel)

	TextButton(
		onClick = onClick,
		modifier = Modifier.semantics { contentDescription = resetDescription }
	) {
		Text(stringResource(R.string.reset_to_default))
	}
}

@Composable
private fun SectionLabel(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.labelSmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(bottom = 4.dp)
	)
}

@Composable
private fun HintText(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.onSurfaceVariant,
		modifier = Modifier.padding(vertical = 12.dp)
	)
}

/** A [HintText] that went wrong: same weight and rhythm, in the error color, so a failed import reads as a problem rather than as one more note. */
@Composable
private fun ErrorText(text: String) {
	Text(
		text = text,
		style = MaterialTheme.typography.bodySmall,
		color = MaterialTheme.colorScheme.error,
		modifier = Modifier.padding(vertical = 12.dp)
	)
}

@Composable
private fun formatWeatherProvider(provider: WeatherProviderType) = when (provider) {
	WeatherProviderType.OPEN_METEO -> stringResource(R.string.provider_open_meteo)
	WeatherProviderType.MET_NORWAY -> stringResource(R.string.provider_met_norway)
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

@Composable
private fun formatSkyColor(preset: SkyColorPreset) = when (preset) {
	SkyColorPreset.NATURAL -> stringResource(R.string.sky_color_natural)
	SkyColorPreset.WARM -> stringResource(R.string.sky_color_warm)
	SkyColorPreset.PASTEL -> stringResource(R.string.sky_color_pastel)
	SkyColorPreset.CYBERPUNK -> stringResource(R.string.sky_color_cyberpunk)
}

@Composable
private fun formatSunColor(preset: SunColorPreset) = when (preset) {
	SunColorPreset.NATURAL -> stringResource(R.string.sun_color_natural)
	SunColorPreset.WHITE -> stringResource(R.string.sun_color_white)
	SunColorPreset.GOLDEN -> stringResource(R.string.sun_color_golden)
	SunColorPreset.ORANGE -> stringResource(R.string.sun_color_orange)
}

@Composable
private fun formatBackdrop(scene: BackdropScene): String {
	val label = when (scene) {
		BackdropScene.NONE -> stringResource(R.string.backdrop_none)
		BackdropScene.METROPOLIS -> stringResource(R.string.backdrop_metropolis)
		BackdropScene.BEACH -> stringResource(R.string.backdrop_beach)
		BackdropScene.MOUNTAINS -> stringResource(R.string.backdrop_mountains)
		BackdropScene.COUNTRYSIDE -> stringResource(R.string.backdrop_countryside)
		BackdropScene.PHOTO -> stringResource(R.string.backdrop_photo)
	}

	return if (scene.drawsScenery) {
		stringResource(R.string.backdrop_not_recommended, label)
	} else {
		label
	}
}

@Composable
private fun formatPhotoBucket(bucket: PhotoBucket) = when (bucket) {
	PhotoBucket.DAY -> stringResource(R.string.photo_bucket_day)
	PhotoBucket.NIGHT -> stringResource(R.string.photo_bucket_night)
	PhotoBucket.DAWN -> stringResource(R.string.photo_bucket_dawn)
	PhotoBucket.DUSK -> stringResource(R.string.photo_bucket_dusk)
}

@Composable
private fun formatPhotoState(isSet: Boolean) = if (isSet) {
	stringResource(R.string.photo_set)
} else {
	stringResource(R.string.photo_unset)
}

@Composable
private fun formatPhotoAction(isSet: Boolean) = if (isSet) {
	stringResource(R.string.photo_replace)
} else {
	stringResource(R.string.photo_choose)
}

@Composable
private fun formatUnit(unit: TemperatureUnit) = when (unit) {
	TemperatureUnit.CELSIUS -> stringResource(R.string.unit_celsius)
	TemperatureUnit.FAHRENHEIT -> stringResource(R.string.unit_fahrenheit)
}

/**
 * [ActivityResultContract] that fires [Intent.ACTION_GET_CONTENT] for the given MIME type and wraps it in [Intent.createChooser], so the system presents every registered handler — including apps like Wallhavend — rather than routing straight to the default photo app.
 * The stock [androidx.activity.result.contract.ActivityResultContracts.GetContent] skips the chooser, which on many devices sends image MIME types directly to Google Photos.
 */
private class ChoosableGetContent: ActivityResultContract<String, Uri?>() {
	override fun createIntent(context: Context, input: String): Intent {
		val content = Intent(Intent.ACTION_GET_CONTENT).apply {
			type = input
			addCategory(Intent.CATEGORY_OPENABLE)
		}

		return Intent.createChooser(content, null)
	}

	override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
		if (resultCode != Activity.RESULT_OK || intent == null) {
			return null
		}

		return intent.data ?: intent.clipData?.firstUriOrNull()
	}
}

private const val MET_NORWAY_URL = "https://api.met.no"
private const val CC_BY_4_URL = "https://creativecommons.org/licenses/by/4.0/"

private fun ClipData.firstUriOrNull(): Uri? = if (itemCount > 0) {
	getItemAt(0).uri
} else {
	null
}
