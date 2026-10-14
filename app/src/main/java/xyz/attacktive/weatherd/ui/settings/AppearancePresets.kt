package xyz.attacktive.weatherd.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.SavedAppearancePreset
import xyz.attacktive.weatherd.domain.repository.APPEARANCE_PRESET_NAME_MAX_LENGTH
import xyz.attacktive.weatherd.domain.repository.AppearancePresetStorageState
import xyz.attacktive.weatherd.domain.repository.appearancePresetNameKey

private sealed interface PresetNameEditor {
	data object Create: PresetNameEditor
	data class Rename(val preset: SavedAppearancePreset): PresetNameEditor
}

private data class PendingPresetReplacement(val preset: SavedAppearancePreset, val name: String)

@Composable
internal fun PresetsSection(
	state: AppearancePresetStorageState,
	mutationState: AppearancePresetMutationState,
	onMutationHandled: () -> Unit,
	onCreate: (String) -> Unit,
	onReplace: (String, String) -> Unit,
	onApply: (SavedAppearancePreset) -> Unit,
	onRename: (String, String) -> Unit,
	onDelete: (String) -> Unit
) {
	SectionLabel(stringResource(R.string.section_presets))
	HintText(stringResource(R.string.hint_presets))

	when (state) {
		is AppearancePresetStorageState.Unreadable -> ErrorText(stringResource(R.string.preset_storage_unreadable))

		is AppearancePresetStorageState.Ready -> {
			var nameEditor by remember { mutableStateOf<PresetNameEditor?>(null) }
			var pendingReplacement by remember { mutableStateOf<PendingPresetReplacement?>(null) }
			var pendingDelete by remember { mutableStateOf<SavedAppearancePreset?>(null) }
			val mutationInProgress = mutationState == AppearancePresetMutationState.InProgress
			val mutationFailed = mutationState == AppearancePresetMutationState.Failed

			LaunchedEffect(mutationState) {
				if (mutationState == AppearancePresetMutationState.Succeeded) {
					nameEditor = null
					pendingReplacement = null
					pendingDelete = null
					onMutationHandled()
				}
			}

			TextButton(
				onClick = {
					onMutationHandled()
					nameEditor = PresetNameEditor.Create
				},
				enabled = !mutationInProgress
			) {
				Text(stringResource(R.string.preset_save_current))
			}

			if (state.presets.isEmpty()) {
				HintText(stringResource(R.string.preset_empty))
			} else {
				state.presets.forEach { preset ->
					PresetRow(
						preset = preset,
						onApply = { onApply(preset) },
						onRename = {
							onMutationHandled()
							nameEditor = PresetNameEditor.Rename(preset)
						},
						onDelete = {
							onMutationHandled()
							pendingDelete = preset
						}
					)
				}
			}

			HintText(stringResource(R.string.hint_preset_photos))

			nameEditor?.let { editor ->
				val renamedPreset = (editor as? PresetNameEditor.Rename)?.preset
				PresetNameDialog(
					title = stringResource(if (renamedPreset == null) R.string.preset_save_title else R.string.preset_rename_title),
					initialName = renamedPreset?.name.orEmpty(),
					nameExists = { candidate ->
						renamedPreset != null && state.presets.any { preset ->
							preset.id != renamedPreset.id && appearancePresetNameKey(preset.name) == appearancePresetNameKey(candidate)
						}
					},
					operationInProgress = mutationInProgress,
					operationFailed = mutationFailed,
					onDismiss = {
						nameEditor = null
						onMutationHandled()
					},
					onConfirm = { name ->
						if (renamedPreset != null) {
							onRename(renamedPreset.id, name)
						} else {
							val existing = state.presets.firstOrNull { preset ->
								appearancePresetNameKey(preset.name) == appearancePresetNameKey(name)
							}

							if (existing != null) {
								pendingReplacement = PendingPresetReplacement(existing, name)
								nameEditor = null
							} else {
								onCreate(name)
							}
						}
					}
				)
			}

			pendingReplacement?.let { replacement ->
				AlertDialog(
					onDismissRequest = {
						if (!mutationInProgress) {
							pendingReplacement = null
							onMutationHandled()
						}
					},
					title = { Text(stringResource(R.string.preset_replace_title)) },
					text = {
						Column {
							Text(stringResource(R.string.preset_replace_message, replacement.preset.name))

							if (mutationFailed) {
								Spacer(modifier = Modifier.height(8.dp))
								ErrorText(stringResource(R.string.preset_operation_failed))
							}
						}
					},
					confirmButton = {
						TextButton(
							onClick = { onReplace(replacement.preset.id, replacement.name) },
							enabled = !mutationInProgress
						) {
							Text(stringResource(R.string.preset_replace))
						}
					},
					dismissButton = {
						TextButton(
							onClick = {
								pendingReplacement = null
								onMutationHandled()
							},
							enabled = !mutationInProgress
						) {
							Text(stringResource(R.string.cancel))
						}
					}
				)
			}

			pendingDelete?.let { preset ->
				AlertDialog(
					onDismissRequest = {
						if (!mutationInProgress) {
							pendingDelete = null
							onMutationHandled()
						}
					},
					title = { Text(stringResource(R.string.preset_delete_title)) },
					text = {
						Column {
							Text(stringResource(R.string.preset_delete_message, preset.name))

							if (mutationFailed) {
								Spacer(modifier = Modifier.height(8.dp))
								ErrorText(stringResource(R.string.preset_operation_failed))
							}
						}
					},
					confirmButton = {
						TextButton(
							onClick = { onDelete(preset.id) },
							enabled = !mutationInProgress
						) {
							Text(stringResource(R.string.preset_delete))
						}
					},
				dismissButton = {
						TextButton(
							onClick = {
								pendingDelete = null
								onMutationHandled()
							},
							enabled = !mutationInProgress
						) {
							Text(stringResource(R.string.cancel))
						}
					}
				)
			}
		}
	}
}

@Composable
private fun PresetRow(preset: SavedAppearancePreset, onApply: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
	var menuExpanded by remember(preset.id) { mutableStateOf(false) }

	Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
		Text(text = preset.name, modifier = Modifier.weight(1f))

		TextButton(onClick = onApply) {
			Text(stringResource(R.string.preset_apply))
		}

		Box {
			IconButton(onClick = { menuExpanded = true }) {
				Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.content_description_preset_options, preset.name))
			}

			DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
				DropdownMenuItem(
					text = { Text(stringResource(R.string.preset_rename)) },
					onClick = {
						menuExpanded = false
						onRename()
					}
				)

				DropdownMenuItem(
					text = { Text(stringResource(R.string.preset_delete)) },
					onClick = {
						menuExpanded = false
						onDelete()
					}
				)
			}
		}
	}
}

@Composable
private fun PresetNameDialog(
	title: String,
	initialName: String,
	nameExists: (String) -> Boolean,
	operationInProgress: Boolean,
	operationFailed: Boolean,
	onDismiss: () -> Unit,
	onConfirm: (String) -> Unit
) {
	var name by remember(initialName) { mutableStateOf(initialName) }
	val normalized = name.trim()
	val duplicate = normalized.isNotEmpty() && nameExists(normalized)
	val canConfirm = normalized.isNotEmpty() && !duplicate && !operationInProgress

	AlertDialog(
		onDismissRequest = {
			if (!operationInProgress) {
				onDismiss()
			}
		},
		title = { Text(title) },
		text = {
			Column {
				OutlinedTextField(
					value = name,
					onValueChange = { value ->
						if (value.length <= APPEARANCE_PRESET_NAME_MAX_LENGTH) {
							name = value
						}
					},
					label = { Text(stringResource(R.string.preset_name)) },
					singleLine = true,
					enabled = !operationInProgress,
					isError = duplicate,
					supportingText = if (duplicate) {
						{ Text(stringResource(R.string.preset_name_exists)) }
					} else {
						null
					}
				)

				if (operationFailed) {
					Spacer(modifier = Modifier.height(8.dp))
					ErrorText(stringResource(R.string.preset_operation_failed))
				}
			}
		},
		confirmButton = {
			TextButton(onClick = { onConfirm(normalized) }, enabled = canConfirm) {
				Text(stringResource(R.string.save))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss, enabled = !operationInProgress) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}
