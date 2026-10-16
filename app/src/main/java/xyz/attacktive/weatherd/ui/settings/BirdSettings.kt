package xyz.attacktive.weatherd.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings
import xyz.attacktive.weatherd.domain.model.BirdAppearanceInterval
import xyz.attacktive.weatherd.domain.model.BirdFlockSize

@Composable
internal fun BirdSettings(
	settings: AppSettings,
	defaults: AppSettings,
	onSave: (AppSettings) -> Unit
) {
	BirdFlockSizePicker(settings = settings, defaults = defaults, onSave = onSave)

	AnimatedVisibility(visible = settings.birdFlockSize != BirdFlockSize.OFF) {
		Column {
			Spacer(modifier = Modifier.height(16.dp))
			BirdAppearanceIntervalPicker(settings = settings, defaults = defaults, onSave = onSave)
		}
	}

	Spacer(modifier = Modifier.height(8.dp))
	HintText(stringResource(R.string.hint_bird_behavior))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirdFlockSizePicker(
	settings: AppSettings,
	defaults: AppSettings,
	onSave: (AppSettings) -> Unit
) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_bird_flock_size),
		isDefault = settings.birdFlockSize == defaults.birdFlockSize,
		onReset = { onSave(settings.copy(birdFlockSize = defaults.birdFlockSize)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatBirdFlockSize(settings.birdFlockSize),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			BirdFlockSize.entries.forEach { size ->
				DropdownMenuItem(
					text = { Text(formatBirdFlockSize(size)) },
					onClick = {
						if (size != settings.birdFlockSize) {
							onSave(settings.copy(birdFlockSize = size))
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
private fun BirdAppearanceIntervalPicker(
	settings: AppSettings,
	defaults: AppSettings,
	onSave: (AppSettings) -> Unit
) {
	var expanded by remember { mutableStateOf(false) }

	ResettableSectionLabel(
		text = stringResource(R.string.section_bird_appearance_interval),
		isDefault = settings.birdAppearanceInterval == defaults.birdAppearanceInterval,
		onReset = { onSave(settings.copy(birdAppearanceInterval = defaults.birdAppearanceInterval)) }
	)

	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = formatBirdAppearanceInterval(settings.birdAppearanceInterval),
			onValueChange = {},
			readOnly = true,
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier
				.fillMaxWidth()
				.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			BirdAppearanceInterval.entries.forEach { interval ->
				DropdownMenuItem(
					text = { Text(formatBirdAppearanceInterval(interval)) },
					onClick = {
						if (interval != settings.birdAppearanceInterval) {
							onSave(settings.copy(birdAppearanceInterval = interval))
						}

						expanded = false
					}
				)
			}
		}
	}
}

@Composable
private fun formatBirdFlockSize(size: BirdFlockSize) = when (size) {
	BirdFlockSize.OFF -> stringResource(R.string.bird_flock_size_off)
	BirdFlockSize.SMALL -> stringResource(R.string.bird_flock_size_small)
	BirdFlockSize.MEDIUM -> stringResource(R.string.bird_flock_size_medium)
	BirdFlockSize.LARGE -> stringResource(R.string.bird_flock_size_large)
}

@Composable
private fun formatBirdAppearanceInterval(interval: BirdAppearanceInterval) = when (interval) {
	BirdAppearanceInterval.NATURAL -> stringResource(R.string.bird_appearance_interval_natural)
	BirdAppearanceInterval.SECONDS_30 -> stringResource(R.string.bird_appearance_interval_30_seconds)
	BirdAppearanceInterval.MINUTE_1 -> stringResource(R.string.bird_appearance_interval_1_minute)
	BirdAppearanceInterval.MINUTES_2 -> stringResource(R.string.bird_appearance_interval_2_minutes)
	BirdAppearanceInterval.MINUTES_5 -> stringResource(R.string.bird_appearance_interval_5_minutes)
	BirdAppearanceInterval.MINUTES_10 -> stringResource(R.string.bird_appearance_interval_10_minutes)
}
