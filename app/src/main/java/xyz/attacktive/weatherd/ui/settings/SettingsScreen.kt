package xyz.attacktive.weatherd.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BackdropScene
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.GeoPlace
import xyz.attacktive.weatherd.domain.model.TemperatureUnit
import xyz.attacktive.weatherd.domain.model.UPDATE_INTERVAL_OPTIONS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onNavigateBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
	val settings by viewModel.settings.collectAsStateWithLifecycle()
	val citySearch by viewModel.citySearch.collectAsStateWithLifecycle()
	val scrollState = rememberScrollState()

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(stringResource(R.string.settings_title)) },
				navigationIcon = {
					IconButton(onClick = onNavigateBack) {
						Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
					}
				}
			)
		}
	) { padding ->
		Column(
			modifier = Modifier
				.fillMaxSize()
				.padding(padding)
		) {
			Column(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth()
					.verticalScroll(scrollState)
					.padding(16.dp)
			) {
				RefreshIntervalSection(settings = settings, onSave = viewModel::save)

				Spacer(modifier = Modifier.height(24.dp))

				FrameRateSection(settings = settings, onSave = viewModel::save)

				Spacer(modifier = Modifier.height(24.dp))

				BackdropSection(settings = settings, onSave = viewModel::save)

				Spacer(modifier = Modifier.height(24.dp))

				LabelsSection(settings = settings, onSave = viewModel::save)

				Spacer(modifier = Modifier.height(24.dp))

				LocationSection(
					settings = settings,
					citySearch = citySearch,
					onToggleDeviceLocation = { viewModel.save(settings.copy(useDeviceLocation = it)) },
					onSearch = viewModel::searchCity,
					onSelectPlace = viewModel::selectPlace,
					onClearManualLocation = viewModel::clearManualLocation
				)
			}

			VersionFooter()
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RefreshIntervalSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	SectionLabel(stringResource(R.string.section_refresh_interval))

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
private fun FrameRateSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	SectionLabel(stringResource(R.string.section_frame_rate))

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackdropSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	var expanded by remember { mutableStateOf(false) }

	SectionLabel(stringResource(R.string.section_backdrop))

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
			BackdropScene.entries.forEach { scene ->
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

@Composable
private fun LabelsSection(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	SectionLabel(stringResource(R.string.section_labels))

	ToggleSetting(
		label = stringResource(R.string.label_show_weather),
		subtitle = stringResource(R.string.subtitle_show_weather),
		checked = settings.showWeatherLabel,
		onToggle = { onSave(settings.copy(showWeatherLabel = it)) }
	)

	AnimatedVisibility(visible = settings.showWeatherLabel) {
		Column {
			Spacer(modifier = Modifier.height(8.dp))

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
		onToggle = { onSave(settings.copy(showLocationLabel = it)) }
	)
}

@Composable
private fun LocationSection(
	settings: AppSettings,
	citySearch: CitySearchState,
	onToggleDeviceLocation: (Boolean) -> Unit,
	onSearch: (String) -> Unit,
	onSelectPlace: (GeoPlace) -> Unit,
	onClearManualLocation: () -> Unit
) {
	SectionLabel(stringResource(R.string.section_location))

	ToggleSetting(
		label = stringResource(R.string.label_use_device_location),
		subtitle = stringResource(R.string.subtitle_use_device_location),
		checked = settings.useDeviceLocation,
		onToggle = onToggleDeviceLocation
	)

	AnimatedVisibility(visible = !settings.useDeviceLocation) {
		Column {
			Spacer(modifier = Modifier.height(8.dp))

			settings.manualLocationLabel?.let { label ->
				CurrentManualLocation(label = label, onClear = onClearManualLocation)
				Spacer(modifier = Modifier.height(8.dp))
			}

			CitySearchField(onSearch = onSearch)

			CitySearchResults(state = citySearch, onSelectPlace = onSelectPlace)
		}
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CitySearchField(onSearch: (String) -> Unit) {
	var query by rememberSaveable { mutableStateOf("") }

	OutlinedTextField(
		value = query,
		onValueChange = { query = it },
		label = { Text(stringResource(R.string.label_city)) },
		placeholder = { Text(stringResource(R.string.placeholder_city)) },
		singleLine = true,
		trailingIcon = {
			IconButton(onClick = { onSearch(query) }) {
				Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.content_description_search))
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
private fun ToggleSetting(label: String, subtitle: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Column(modifier = Modifier.weight(1f)) {
			Text(label)
			Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}

		Switch(checked = checked, onCheckedChange = onToggle)
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
private fun formatBackdrop(scene: BackdropScene) = when (scene) {
	BackdropScene.NONE -> stringResource(R.string.backdrop_none)
	BackdropScene.METROPOLIS -> stringResource(R.string.backdrop_metropolis)
	BackdropScene.BEACH -> stringResource(R.string.backdrop_beach)
	BackdropScene.MOUNTAINS -> stringResource(R.string.backdrop_mountains)
	BackdropScene.COUNTRYSIDE -> stringResource(R.string.backdrop_countryside)
}

@Composable
private fun formatUnit(unit: TemperatureUnit) = when (unit) {
	TemperatureUnit.CELSIUS -> stringResource(R.string.unit_celsius)
	TemperatureUnit.FAHRENHEIT -> stringResource(R.string.unit_fahrenheit)
}
