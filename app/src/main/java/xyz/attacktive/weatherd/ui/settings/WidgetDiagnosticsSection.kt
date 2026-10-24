package xyz.attacktive.weatherd.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.diagnostics.WidgetDiagnostics

@Composable
internal fun WidgetDiagnosticsSection(modifier: Modifier = Modifier) {
	val context = LocalContext.current
	val diagnostics = remember(context) {
		WidgetDiagnostics.get(context)
	}
	val state by diagnostics.state.collectAsStateWithLifecycle()
	val coroutineScope = rememberCoroutineScope()

	var isBusy by remember { mutableStateOf(false) }
	var operationFailed by rememberSaveable { mutableStateOf(false) }
	var showClearDialog by rememberSaveable { mutableStateOf(false) }
	var previewReportText by rememberSaveable { mutableStateOf<String?>(null) }

	val ready = state.ready
	val controlsEnabled = ready && !isBusy
	val emptyReportText = stringResource(R.string.widget_diagnostics_report_empty)

	Column(modifier = modifier.fillMaxWidth()) {
		SectionLabel(stringResource(R.string.section_widget_diagnostics))

		Spacer(modifier = Modifier.height(4.dp))

		ToggleSetting(
			label = stringResource(R.string.label_widget_diagnostics_capture),
			subtitle = stringResource(R.string.subtitle_widget_diagnostics_capture),
			checked = state.enabled,
			onToggle = { enabled ->
				if (controlsEnabled) {
					isBusy = true
					operationFailed = false

					coroutineScope.launch {
						try {
							val result = diagnostics.setEnabled(enabled)

							if (result.isFailure) {
								operationFailed = true
							}
						} catch (e: Exception) {
							if (e is CancellationException) {
								throw e
							}

							operationFailed = true
						} finally {
							isBusy = false
						}
					}
				}
			},
			enabled = controlsEnabled
		)

		Spacer(modifier = Modifier.height(8.dp))

		HintText(stringResource(R.string.widget_diagnostics_instructions))

		Spacer(modifier = Modifier.height(4.dp))

		HintText(stringResource(R.string.widget_diagnostics_privacy))

		if (state.storageFailed) {
			Spacer(modifier = Modifier.height(8.dp))
			ErrorText(stringResource(R.string.widget_diagnostics_status_storage_failed))
		}

		if (state.droppedEvents > 0L) {
			Spacer(modifier = Modifier.height(8.dp))
			ErrorText(stringResource(R.string.widget_diagnostics_status_dropped_events, state.droppedEvents))
		}

		if (operationFailed) {
			Spacer(modifier = Modifier.height(8.dp))
			ErrorText(stringResource(R.string.widget_diagnostics_operation_failed))
		}

		Spacer(modifier = Modifier.height(8.dp))

		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.spacedBy(8.dp)
		) {
			TextButton(
				onClick = {
					if (controlsEnabled) {
						isBusy = true
						operationFailed = false

						coroutineScope.launch {
							try {
								val result = diagnostics.report()

								if (result.isSuccess) {
									val reportText = result.getOrNull()
									previewReportText = if (reportText.isNullOrBlank()) {
										emptyReportText
									} else {
										reportText
									}
								} else {
									operationFailed = true
								}
							} catch (e: Exception) {
								if (e is CancellationException) {
									throw e
								}

								operationFailed = true
							} finally {
								isBusy = false
							}
						}
					}
				},
				enabled = controlsEnabled
			) {
				Text(stringResource(R.string.widget_diagnostics_share))
			}

			TextButton(
				onClick = {
					if (controlsEnabled) {
						operationFailed = false
						showClearDialog = true
					}
				},
				enabled = controlsEnabled
			) {
				Text(stringResource(R.string.widget_diagnostics_clear))
			}
		}
	}

	if (showClearDialog) {
		AlertDialog(
			onDismissRequest = {
				if (!isBusy) {
					showClearDialog = false
				}
			},
			title = {
				Text(stringResource(R.string.widget_diagnostics_clear_confirm_title))
			},
			text = {
				Column {
					Text(stringResource(R.string.widget_diagnostics_clear_confirm_message))

					if (operationFailed) {
						Spacer(modifier = Modifier.height(8.dp))
						ErrorText(stringResource(R.string.widget_diagnostics_operation_failed))
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						if (!isBusy) {
							isBusy = true
							operationFailed = false

							coroutineScope.launch {
								try {
									val result = diagnostics.clear()

									if (result.isSuccess) {
										showClearDialog = false
									} else {
										operationFailed = true
									}
								} catch (e: Exception) {
									if (e is CancellationException) {
										throw e
									}

									operationFailed = true
								} finally {
									isBusy = false
								}
							}
						}
					},
					enabled = !isBusy
				) {
					Text(stringResource(R.string.widget_diagnostics_clear))
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						if (!isBusy) {
							showClearDialog = false
						}
					},
					enabled = !isBusy
				) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}

	previewReportText?.let { reportText ->
		val shareTitle = stringResource(R.string.widget_diagnostics_share_chooser_title)

		AlertDialog(
			onDismissRequest = {
				previewReportText = null
			},
			title = {
				Text(stringResource(R.string.widget_diagnostics_report_title))
			},
			text = {
				Column(
					modifier = Modifier
						.fillMaxWidth()
						.verticalScroll(rememberScrollState())
				) {
					SelectionContainer {
						Text(
							text = reportText,
							style = MaterialTheme.typography.bodySmall
						)
					}
				}
			},
			confirmButton = {
				TextButton(
					onClick = {
						val intent = Intent(Intent.ACTION_SEND).apply {
							type = "text/plain"
							putExtra(Intent.EXTRA_TEXT, reportText)
						}

						try {
							context.startActivity(Intent.createChooser(intent, shareTitle))
						} catch (_: ActivityNotFoundException) {
							// Without a compatible share target, keep the selectable report preview visible
						}
					}
				) {
					Text(stringResource(R.string.widget_diagnostics_share_button))
				}
			},
			dismissButton = {
				TextButton(
					onClick = {
						previewReportText = null
					}
				) {
					Text(stringResource(R.string.cancel))
				}
			}
		)
	}
}
