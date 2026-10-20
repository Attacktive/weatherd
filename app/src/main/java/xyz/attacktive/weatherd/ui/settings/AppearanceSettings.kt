package xyz.attacktive.weatherd.ui.settings

import kotlin.math.roundToInt
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.repository.AppearancePresetStorageState

internal enum class AppearanceCategory(@StringRes val label: Int) {
	SCENERY(R.string.appearance_category_scenery),
	SKY(R.string.appearance_category_sky),
	CLOUDS(R.string.appearance_category_clouds),
	SUN(R.string.appearance_category_sun),
	WEATHER_EFFECTS(R.string.appearance_category_weather_effects),
	TEXT(R.string.appearance_category_text),
	READABILITY(R.string.appearance_category_readability),
	PRESETS(R.string.appearance_category_presets)
}

@Composable
internal fun AppearanceSettingsTab(viewModel: SettingsViewModel, category: AppearanceCategory?, onSelectCategory: (AppearanceCategory) -> Unit) {
	val settings by viewModel.settings.collectAsStateWithLifecycle()
	val onSave = settingsChangeCallback(settings, viewModel::applySettingsChange)
	val scrollState = rememberScrollState()

	SettingsTabContent(scrollState) {
		when (category) {
			null -> {
				val presetState by viewModel.appearancePresets.collectAsStateWithLifecycle()

				AppearanceHub(settings, presetState, onSelectCategory)
			}

			AppearanceCategory.SCENERY -> ScenerySettings(viewModel, settings, onSave)

			AppearanceCategory.SKY -> {
				SkyAppearanceSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
				Spacer(modifier = Modifier.height(24.dp))
				SkyDetailsSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
			}

			AppearanceCategory.CLOUDS -> CloudAppearanceSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
			AppearanceCategory.SUN -> SunEffectsSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
			AppearanceCategory.WEATHER_EFFECTS -> WeatherEffectsSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
			AppearanceCategory.TEXT -> LabelsSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
			AppearanceCategory.READABILITY -> ReadabilitySection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
			AppearanceCategory.PRESETS -> PresetSettings(viewModel)
		}
	}
}

@Composable
private fun AppearanceHub(settings: AppSettings, presetState: AppearancePresetStorageState, onSelectCategory: (AppearanceCategory) -> Unit) = AppearanceCategory.entries.forEach { category ->
	ListItem(
		headlineContent = { Text(stringResource(category.label)) },
		supportingContent = {
			Text(
				text = appearanceSummary(category, settings, presetState),
				maxLines = 2,
				overflow = TextOverflow.Ellipsis
			)
		},
		trailingContent = { Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null) },
		modifier = Modifier.clickable(role = Role.Button, onClick = { onSelectCategory(category) })
	)
}

@Composable
private fun appearanceSummary(category: AppearanceCategory, settings: AppSettings, presetState: AppearancePresetStorageState) = when (category) {
	AppearanceCategory.SCENERY -> stringResource(
		R.string.appearance_summary_scenery,
		formatBackdrop(settings.backdropScene, includeRecommendation = false),
		stringResource(if (settings.treesEnabled) R.string.appearance_summary_trees_on else R.string.appearance_summary_trees_off)
	)

	AppearanceCategory.SKY -> stringResource(
		R.string.appearance_summary_sky,
		formatSkyColor(settings.skyColorPreset),
		(settings.skyBrightnessScale * 100f).roundToInt()
	)

	AppearanceCategory.CLOUDS -> stringResource(
		R.string.appearance_summary_clouds,
		(settings.cloudCountScale * 100f).roundToInt(),
		(settings.cloudIntensityScale * 100f).roundToInt()
	)

	AppearanceCategory.SUN -> stringResource(
		R.string.appearance_summary_sun,
		formatSunStyle(settings.sunStyle),
		stringResource(if (settings.sunVisible) R.string.appearance_summary_sun_shown else R.string.appearance_summary_sun_hidden)
	)

	AppearanceCategory.WEATHER_EFFECTS -> stringResource(
		R.string.appearance_summary_weather_effects,
		(settings.precipitationIntensityScale * 100f).roundToInt(),
		(settings.windIntensityScale * 100f).roundToInt()
	)

	AppearanceCategory.TEXT -> stringResource(
		when {
			settings.showWeatherLabel && settings.showLocationLabel -> R.string.appearance_summary_text_both
			settings.showWeatherLabel -> R.string.appearance_summary_text_weather
			settings.showLocationLabel -> R.string.appearance_summary_text_location
			else -> R.string.appearance_summary_text_hidden
		}
	)

	AppearanceCategory.READABILITY -> stringResource(
		R.string.appearance_summary_readability,
		(settings.wallpaperDimmingStrength * 100f).roundToInt(),
		(settings.statusBarShadeStrength * 100f).roundToInt()
	)

	AppearanceCategory.PRESETS -> when (presetState) {
		is AppearancePresetStorageState.Ready -> pluralStringResource(R.plurals.appearance_summary_presets, presetState.presets.size, presetState.presets.size)
		is AppearancePresetStorageState.Unreadable -> stringResource(R.string.appearance_summary_presets_unavailable)
	}
}

@Composable
private fun ScenerySettings(viewModel: SettingsViewModel, settings: AppSettings, onSave: (AppSettings) -> Unit) {
	BackdropSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)

	AnimatedVisibility(visible = settings.backdropScene == BackdropScene.PHOTO) {
		val photoBuckets by viewModel.photoBuckets.collectAsStateWithLifecycle()
		val photoThumbnails by viewModel.photoThumbnails.collectAsStateWithLifecycle()
		val photoImportFailed by viewModel.photoImportFailed.collectAsStateWithLifecycle()

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
	TreesSection(settings = settings, defaults = viewModel.defaults, onSave = onSave)
}

@Composable
private fun PresetSettings(viewModel: SettingsViewModel) {
	val presetState by viewModel.appearancePresets.collectAsStateWithLifecycle()
	val mutationState by viewModel.appearancePresetMutationState.collectAsStateWithLifecycle()

	PresetsSection(
		state = presetState,
		mutationState = mutationState,
		onMutationHandled = viewModel::clearAppearancePresetMutationState,
		onCreate = viewModel::createAppearancePreset,
		onReplace = viewModel::replaceAppearancePreset,
		onApply = viewModel::applyAppearancePreset,
		onRename = viewModel::renameAppearancePreset,
		onDelete = viewModel::deleteAppearancePreset
	)
}
