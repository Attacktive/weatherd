package xyz.attacktive.weatherd.ui.settings

import java.time.LocalDate
import java.time.Month
import java.time.MonthDay
import java.time.format.TextStyle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.AppSettings

@Composable
internal fun BirthdaySettings(settings: AppSettings, onSave: (AppSettings) -> Unit) {
	var dateDialogOpen by rememberSaveable { mutableStateOf(false) }
	SectionLabel(stringResource(R.string.birthday_title))
	ToggleSetting(
		label = stringResource(R.string.birthday_enable),
		subtitle = stringResource(R.string.birthday_hint),
		checked = settings.birthdayCelebrationEnabled,
		onToggle = { onSave(settings.copy(birthdayCelebrationEnabled = it)) }
	)

	if (settings.birthdayCelebrationEnabled) {
		BirthdayEveryDayCheckbox(
			checked = settings.birthdayEveryDay,
			onChange = { onSave(settings.copy(birthdayEveryDay = it)) }
		)

		if (!settings.birthdayEveryDay) {
			BirthdayDateSetting(
				date = settings.birthdayDate,
				onChoose = { dateDialogOpen = true },
				onClear = { onSave(settings.copy(birthdayDate = null)) }
			)
		}
	}

	if (dateDialogOpen && settings.birthdayCelebrationEnabled && !settings.birthdayEveryDay) {
		BirthdayDateDialog(
			initial = settings.birthdayDate ?: MonthDay.from(LocalDate.now()),
			onDismiss = { dateDialogOpen = false },
			onConfirm = {
				onSave(settings.copy(birthdayDate = it))
				dateDialogOpen = false
			}
		)
	}
}

@Composable
private fun BirthdayEveryDayCheckbox(checked: Boolean, onChange: (Boolean) -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth().toggleable(
			value = checked,
			role = Role.Checkbox,
			onValueChange = onChange
		).padding(vertical = 8.dp),
		verticalAlignment = Alignment.CenterVertically
	) {
		Checkbox(checked = checked, onCheckedChange = null)
		Text(stringResource(R.string.birthday_every_day), modifier = Modifier.padding(start = 12.dp))
	}
}

@Composable
private fun BirthdayDateSetting(date: MonthDay?, onChoose: () -> Unit, onClear: () -> Unit) {
	val locale = LocalConfiguration.current.locales[0]
	val dateText = if (date == null) {
		stringResource(R.string.birthday_choose_date)
	} else {
		val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMd")
		date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
	}

	OutlinedButton(onClick = onChoose, modifier = Modifier.fillMaxWidth()) {
		Text(dateText)
	}

	if (date != null) {
		TextButton(onClick = onClear) {
			Text(stringResource(R.string.birthday_clear_date))
		}
	}
}

@Composable
private fun BirthdayDateDialog(initial: MonthDay, onDismiss: () -> Unit, onConfirm: (MonthDay) -> Unit) {
	var month by rememberSaveable { mutableIntStateOf(initial.monthValue) }
	var day by rememberSaveable { mutableIntStateOf(initial.dayOfMonth) }
	val locale = LocalConfiguration.current.locales[0]
	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(stringResource(R.string.birthday_choose_date)) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				BirthdayNumberPicker(
					label = stringResource(R.string.birthday_month),
					value = month,
					choices = 1..12,
					format = { Month.of(it).getDisplayName(TextStyle.FULL, locale) },
					onSelect = {
						month = it
						day = minOf(day, Month.of(it).maxLength())
					}
				)

				Spacer(modifier = Modifier.height(12.dp))
				BirthdayNumberPicker(
					label = stringResource(R.string.birthday_day),
					value = day,
					choices = 1..Month.of(month).maxLength(),
					format = { String.format(locale, "%d", it) },
					onSelect = { day = it }
				)

				Spacer(modifier = Modifier.height(12.dp))
				HintText(stringResource(R.string.birthday_date_hint))
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(MonthDay.of(month, day)) }) {
				Text(stringResource(R.string.birthday_save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.birthday_cancel))
			}
		}
	)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayNumberPicker(label: String, value: Int, choices: IntRange, format: (Int) -> String, onSelect: (Int) -> Unit) {
	var expanded by rememberSaveable { mutableStateOf(false) }
	ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
		OutlinedTextField(
			value = format(value),
			onValueChange = {},
			readOnly = true,
			label = { Text(label) },
			trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
			modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
		)

		ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
			for (choice in choices) {
				DropdownMenuItem(
					text = { Text(format(choice)) },
					onClick = {
						onSelect(choice)
						expanded = false
					}
				)
			}
		}
	}
}
