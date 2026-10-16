package xyz.attacktive.weatherd.ui.settings

import kotlin.math.roundToInt
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.CLOUD_CONTRAST_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_COUNT_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.CLOUD_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.INTENSITY_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.LABEL_VERTICAL_POSITION_RANGE
import xyz.attacktive.weatherd.domain.model.NIGHT_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.PhotoBucket
import xyz.attacktive.weatherd.domain.model.READABILITY_STRENGTH_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_BRIGHTNESS_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SKY_SATURATION_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SUN_SIZE_SCALE_RANGE
import xyz.attacktive.weatherd.domain.model.SkyColorPreset
import xyz.attacktive.weatherd.domain.model.SunColorPreset
import xyz.attacktive.weatherd.domain.model.SunStyle
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.drawsScenery
import xyz.attacktive.weatherd.platform.lensFlareMotionSupported

private val BACKDROP_SCENE_DISPLAY_ORDER = listOf(
	BackdropScene.NONE,
	BackdropScene.PHOTO,
	BackdropScene.METROPOLIS,
	BackdropScene.BEACH,
	BackdropScene.MOUNTAINS,
	BackdropScene.COUNTRYSIDE
)

@Composable
internal fun WeatherEffectsSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	IntensitySlider(
		label = stringResource(R.string.section_precipitation_intensity),
		value = settings.precipitationIntensityScale,
		defaultValue = defaults.precipitationIntensityScale,
		onCommit = { onSave(settings.copy(precipitationIntensityScale = it)) }
	)

	Spacer(modifier = Modifier.height(12.dp))

	ToggleSetting(
		label = stringResource(R.string.label_glass_droplets),
		subtitle = stringResource(R.string.subtitle_glass_droplets),
		checked = settings.glassDropletsEnabled,
		onToggle = { onSave(settings.copy(glassDropletsEnabled = it)) }
	)

	Spacer(modifier = Modifier.height(12.dp))

	IntensitySlider(
		label = stringResource(R.string.section_wind_intensity),
		value = settings.windIntensityScale,
		defaultValue = defaults.windIntensityScale,
		onCommit = { onSave(settings.copy(windIntensityScale = it)) }
	)

	HintText(stringResource(R.string.appearance_hint_weather_effects))
}

@Composable
internal fun SkyAppearanceSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
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

@Composable
internal fun SkyDetailsSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	SectionLabel(stringResource(R.string.section_sky_details))

	ToggleSetting(
		label = stringResource(R.string.label_distant_aircraft),
		subtitle = stringResource(R.string.subtitle_distant_aircraft),
		checked = settings.distantAircraftEnabled,
		onToggle = { onSave(settings.copy(distantAircraftEnabled = it)) }
	)

	Spacer(modifier = Modifier.height(16.dp))
	BirdSettings(settings = settings, defaults = defaults, onSave = onSave)
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
internal fun CloudAppearanceSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
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

	IntensitySlider(
		label = stringResource(R.string.section_cloud_opacity),
		value = settings.cloudIntensityScale,
		defaultValue = defaults.cloudIntensityScale,
		onCommit = { onSave(settings.copy(cloudIntensityScale = it)) }
	)

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
internal fun SunEffectsSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	val context = LocalContext.current
	val motionSupported = remember(context) { lensFlareMotionSupported(context) }
	val motionSubtitle = if (motionSupported) {
		stringResource(R.string.subtitle_lens_flare_motion)
	} else {
		stringResource(R.string.subtitle_lens_flare_motion_unsupported)
	}

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
			SunStylePicker(settings = settings, defaults = defaults, onSave = onSave)
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

			ToggleSetting(
				label = stringResource(R.string.label_lens_flare_motion),
				subtitle = motionSubtitle,
				checked = settings.lensFlareMotionEnabled,
				onToggle = { onSave(settings.copy(lensFlareMotionEnabled = it)) },
				enabled = motionSupported && settings.lensFlareEnabled,
			)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SunStylePicker(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.label_sun_style),
		isDefault = settings.sunStyle == defaults.sunStyle,
		onReset = { onSave(settings.copy(sunStyle = defaults.sunStyle)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatSunStyle(settings.sunStyle),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			SunStyle.entries.forEach { style ->
				DropdownMenuItem(
					text = { Text(formatSunStyle(style)) },
					onClick = {
						if (style != settings.sunStyle) {
							onSave(settings.copy(sunStyle = style))
						}

						expanded = false
					}
				)
			}
		}
	}

	if (settings.sunStyle == SunStyle.RADIANT) {
		HintText(stringResource(R.string.hint_radiant_sun))
	}
}

@Composable
private fun SunSizeSlider(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) = PercentageSlider(
	label = R.string.label_sun_size,
	value = settings.sunSizeScale,
	defaultValue = defaults.sunSizeScale,
	valueRange = SUN_SIZE_SCALE_RANGE,
	lowLabel = R.string.sun_size_small,
	highLabel = R.string.sun_size_large,
	onCommit = { onSave(settings.copy(sunSizeScale = it)) }
)

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
internal fun BackdropSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
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
internal fun PhotoBackgroundSection(
	buckets: Set<PhotoBucket>,
	thumbnails: Map<PhotoBucket, Bitmap>,
	importFailed: Boolean,
	onChoose: (PhotoBucket, Uri) -> Unit,
	onClear: (PhotoBucket) -> Unit,
	onDismissFailure: () -> Unit
) {
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

	if (PhotoBucket.DAY !in buckets && PhotoBucket.NIGHT !in buckets) {
		HintText(stringResource(R.string.hint_photo_incomplete))
	}
}

@Composable
private fun PhotoBucketRow(bucket: PhotoBucket, isSet: Boolean, thumbnail: Bitmap?, onChoose: (Uri) -> Unit, onClear: () -> Unit) {
	val contract = remember { ChoosableGetContent() }
	val picker = rememberLauncherForActivityResult(contract) { picked ->
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
			val clearDescription = "${formatPhotoBucket(bucket)}, ${stringResource(R.string.photo_clear)}"

			IconButton(onClick = onClear) {
				Icon(Icons.Filled.Clear, contentDescription = clearDescription)
			}
		}
	}
}

@Composable
private fun PhotoBucketPreview(thumbnail: Bitmap?) = if (thumbnail != null) {
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

@Composable
internal fun TreesSection(settings: AppSettings, onSave: (AppSettings) -> Unit) = ToggleSetting(
	label = stringResource(R.string.label_trees),
	subtitle = stringResource(R.string.subtitle_trees),
	checked = settings.treesEnabled,
	onToggle = { onSave(settings.copy(treesEnabled = it)) }
)

@Composable
internal fun ReadabilitySection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
	PercentageSlider(
		label = R.string.section_wallpaper_dimming,
		value = settings.wallpaperDimmingStrength,
		defaultValue = defaults.wallpaperDimmingStrength,
		valueRange = READABILITY_STRENGTH_RANGE,
		lowLabel = R.string.readability_off,
		highLabel = R.string.readability_stronger,
		onCommit = { onSave(settings.copy(wallpaperDimmingStrength = it)) }
	)

	HintText(stringResource(R.string.hint_wallpaper_dimming))
	Spacer(modifier = Modifier.height(24.dp))

	PercentageSlider(
		label = R.string.section_status_bar_shade,
		value = settings.statusBarShadeStrength,
		defaultValue = defaults.statusBarShadeStrength,
		valueRange = READABILITY_STRENGTH_RANGE,
		lowLabel = R.string.readability_off,
		highLabel = R.string.readability_stronger,
		onCommit = { onSave(settings.copy(statusBarShadeStrength = it)) }
	)

	HintText(stringResource(R.string.hint_status_bar_shade))
	Spacer(modifier = Modifier.height(16.dp))
	HintText(stringResource(R.string.hint_readability_surfaces))
}

@Composable
internal fun LabelsSection(settings: AppSettings, defaults: AppSettings, onSave: (AppSettings) -> Unit) {
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

	AnimatedVisibility(visible = settings.showWeatherLabel || settings.showLocationLabel) {
		Column {
			Spacer(modifier = Modifier.height(12.dp))

			PercentageSlider(
				label = R.string.label_vertical_position,
				value = settings.labelVerticalPosition,
				defaultValue = defaults.labelVerticalPosition,
				valueRange = LABEL_VERTICAL_POSITION_RANGE,
				lowLabel = R.string.label_vertical_position_top,
				highLabel = R.string.label_vertical_position_bottom,
				onCommit = { onSave(settings.copy(labelVerticalPosition = it)) }
			)
		}
	}
}

@Composable
internal fun formatSkyColor(preset: SkyColorPreset) = when (preset) {
	SkyColorPreset.NATURAL -> stringResource(R.string.sky_color_natural)
	SkyColorPreset.WARM -> stringResource(R.string.sky_color_warm)
	SkyColorPreset.PASTEL -> stringResource(R.string.sky_color_pastel)
	SkyColorPreset.CYBERPUNK -> stringResource(R.string.sky_color_cyberpunk)
}

@Composable
internal fun formatSunStyle(style: SunStyle) = when (style) {
	SunStyle.CLASSIC -> stringResource(R.string.sun_style_classic)
	SunStyle.RADIANT -> stringResource(R.string.sun_style_radiant)
}

@Composable
private fun formatSunColor(preset: SunColorPreset) = when (preset) {
	SunColorPreset.NATURAL -> stringResource(R.string.sun_color_natural)
	SunColorPreset.WHITE -> stringResource(R.string.sun_color_white)
	SunColorPreset.GOLDEN -> stringResource(R.string.sun_color_golden)
	SunColorPreset.ORANGE -> stringResource(R.string.sun_color_orange)
}

@Composable
internal fun formatBackdrop(scene: BackdropScene, includeRecommendation: Boolean = true): String {
	val label = when (scene) {
		BackdropScene.NONE -> stringResource(R.string.backdrop_none)
		BackdropScene.METROPOLIS -> stringResource(R.string.backdrop_metropolis)
		BackdropScene.BEACH -> stringResource(R.string.backdrop_beach)
		BackdropScene.MOUNTAINS -> stringResource(R.string.backdrop_mountains)
		BackdropScene.COUNTRYSIDE -> stringResource(R.string.backdrop_countryside)
		BackdropScene.PHOTO -> stringResource(R.string.backdrop_photo)
	}

	return if (scene.drawsScenery && includeRecommendation) {
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

private fun ClipData.firstUriOrNull(): Uri? = if (itemCount > 0) {
	getItemAt(0).uri
} else {
	null
}
