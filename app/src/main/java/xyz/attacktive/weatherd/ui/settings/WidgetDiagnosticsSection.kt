package xyz.attacktive.weatherd.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
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
				runDiagnosticsOperation(
					enabled = controlsEnabled,
					coroutineScope = coroutineScope,
					setBusy = { isBusy = it },
					setOperationFailed = { operationFailed = it },
					action = { diagnostics.setEnabled(enabled) }
				)
			},
			enabled = controlsEnabled
		)

		Spacer(modifier = Modifier.height(8.dp))

		HintText(stringResource(R.string.widget_diagnostics_instructions))

		Spacer(modifier = Modifier.height(4.dp))

		HintText(stringResource(R.string.widget_diagnostics_privacy))

		WidgetDiagnosticsStatusMessages(
			storageFailed = state.storageFailed,
			droppedEvents = state.droppedEvents,
			operationFailed = operationFailed
		)

		Spacer(modifier = Modifier.height(8.dp))

		WidgetDiagnosticsActionButtons(
			enabled = controlsEnabled,
			onShare = {
				runDiagnosticsOperation(
					enabled = controlsEnabled,
					coroutineScope = coroutineScope,
					setBusy = { isBusy = it },
					setOperationFailed = { operationFailed = it },
					action = { diagnostics.report() },
					onSuccess = { reportText ->
						previewReportText = resolveReportText(reportText, emptyReportText)
					}
				)
			},
			onClear = {
				if (controlsEnabled) {
					operationFailed = false
					showClearDialog = true
				}
			}
		)
	}

	WidgetDiagnosticsDialogs(
		showClearDialog = showClearDialog,
		previewReportText = previewReportText,
		isBusy = isBusy,
		operationFailed = operationFailed,
		onConfirmClear = {
			runDiagnosticsOperation(
				enabled = !isBusy,
				coroutineScope = coroutineScope,
				setBusy = { isBusy = it },
				setOperationFailed = { operationFailed = it },
				action = { diagnostics.clear() },
				onSuccess = {
					showClearDialog = false
				}
			)
		},
		onDismissClear = { showClearDialog = false },
		onDismissPreview = { previewReportText = null }
	)
}

@Composable
private fun WidgetDiagnosticsStatusMessages(
	storageFailed: Boolean,
	droppedEvents: Long,
	operationFailed: Boolean
) {
	if (storageFailed) {
		Spacer(modifier = Modifier.height(8.dp))
		ErrorText(stringResource(R.string.widget_diagnostics_status_storage_failed))
	}

	if (droppedEvents > 0L) {
		Spacer(modifier = Modifier.height(8.dp))
		ErrorText(stringResource(R.string.widget_diagnostics_status_dropped_events, droppedEvents))
	}

	if (operationFailed) {
		Spacer(modifier = Modifier.height(8.dp))
		ErrorText(stringResource(R.string.widget_diagnostics_operation_failed))
	}
}

@Composable
private fun WidgetDiagnosticsActionButtons(
	enabled: Boolean,
	onShare: () -> Unit,
	onClear: () -> Unit
) = Row(
	modifier = Modifier.fillMaxWidth(),
	horizontalArrangement = Arrangement.spacedBy(8.dp)
) {
	TextButton(
		onClick = onShare,
		enabled = enabled
	) {
		Text(stringResource(R.string.widget_diagnostics_share))
	}

	TextButton(
		onClick = onClear,
		enabled = enabled
	) {
		Text(stringResource(R.string.widget_diagnostics_clear))
	}
}

@Composable
private fun WidgetDiagnosticsDialogs(
	showClearDialog: Boolean,
	previewReportText: String?,
	isBusy: Boolean,
	operationFailed: Boolean,
	onConfirmClear: () -> Unit,
	onDismissClear: () -> Unit,
	onDismissPreview: () -> Unit
) {
	if (showClearDialog) {
		WidgetDiagnosticsClearDialog(
			isBusy = isBusy,
			operationFailed = operationFailed,
			onConfirm = onConfirmClear,
			onDismiss = onDismissClear
		)
	}

	if (previewReportText != null) {
		WidgetDiagnosticsReportDialog(
			reportText = previewReportText,
			onDismiss = onDismissPreview
		)
	}
}

@Composable
private fun WidgetDiagnosticsClearDialog(
	isBusy: Boolean,
	operationFailed: Boolean,
	onConfirm: () -> Unit,
	onDismiss: () -> Unit
) = AlertDialog(
	onDismissRequest = {
		if (!isBusy) {
			onDismiss()
		}
	},
	title = {
		Text(stringResource(R.string.widget_diagnostics_clear_confirm_title))
	},
	text = {
		ClearDialogContent(operationFailed)
	},
	confirmButton = {
		TextButton(
			onClick = {
				if (!isBusy) {
					onConfirm()
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
					onDismiss()
				}
			},
			enabled = !isBusy
		) {
			Text(stringResource(R.string.cancel))
		}
	}
)

@Composable
private fun ClearDialogContent(operationFailed: Boolean) = Column {
	Text(stringResource(R.string.widget_diagnostics_clear_confirm_message))

	if (operationFailed) {
		Spacer(modifier = Modifier.height(8.dp))
		ErrorText(stringResource(R.string.widget_diagnostics_operation_failed))
	}
}

@Composable
private fun WidgetDiagnosticsReportDialog(
	reportText: String,
	onDismiss: () -> Unit
) {
	val context = LocalContext.current
	val shareTitle = stringResource(R.string.widget_diagnostics_share_chooser_title)

	AlertDialog(
		onDismissRequest = onDismiss,
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
					shareReport(context, reportText, shareTitle)
				}
			) {
				Text(stringResource(R.string.widget_diagnostics_share_button))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}

private fun shareReport(context: Context, reportText: String, chooserTitle: String) {
	val intent = Intent(Intent.ACTION_SEND).apply {
		type = "text/plain"
		putExtra(Intent.EXTRA_TEXT, reportText)
	}

	try {
		context.startActivity(Intent.createChooser(intent, chooserTitle))
	} catch (_: ActivityNotFoundException) {
		// Without a compatible share target, keep the selectable report preview visible
	}
}

private fun resolveReportText(report: String, emptyFallback: String) = if (report.isBlank()) {
	emptyFallback
} else {
	report
}

private fun <T> runDiagnosticsOperation(
	enabled: Boolean,
	coroutineScope: CoroutineScope,
	setBusy: (Boolean) -> Unit,
	setOperationFailed: (Boolean) -> Unit,
	action: suspend () -> Result<T>,
	onSuccess: (T) -> Unit = { }
) {
	if (!enabled) {
		return
	}

	setBusy(true)
	setOperationFailed(false)

	coroutineScope.launch {
		try {
			val result = action()
			if (result.isSuccess) {
				onSuccess(result.getOrThrow())
			} else {
				setOperationFailed(true)
			}
		} catch (e: Exception) {
			if (e is CancellationException) {
				throw e
			}

			setOperationFailed(true)
		} finally {
			setBusy(false)
		}
	}
}
