package xyz.attacktive.weatherd.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.data.provider.ObservedSky
import xyz.attacktive.weatherd.data.provider.WeatherComparisonCapture
import xyz.attacktive.weatherd.data.provider.WeatherComparisonService
import xyz.attacktive.weatherd.domain.model.AppSettings

internal sealed interface WeatherComparisonUiState {
	data object Idle: WeatherComparisonUiState
	data object Loading: WeatherComparisonUiState
	data object NoLocation: WeatherComparisonUiState
	data object Failed: WeatherComparisonUiState
	data class Ready(val capture: WeatherComparisonCapture): WeatherComparisonUiState
}

@HiltViewModel
class WeatherComparisonViewModel @Inject constructor(private val comparison: WeatherComparisonService): ViewModel() {
	private val _state = MutableStateFlow<WeatherComparisonUiState>(WeatherComparisonUiState.Idle)
	internal val state = _state.asStateFlow()
	private var comparisonJob: Job? = null

	fun compare(settings: AppSettings) {
		if (_state.value is WeatherComparisonUiState.Loading) {
			return
		}

		comparisonJob?.cancel()
		_state.value = WeatherComparisonUiState.Loading
		comparisonJob = viewModelScope.launch {
			try {
				val capture = comparison.capture(settings)
				_state.value = if (capture == null) {
					WeatherComparisonUiState.NoLocation
				} else {
					WeatherComparisonUiState.Ready(capture)
				}
			} catch (cancellationException: CancellationException) {
				throw cancellationException
			} catch (_: Exception) {
				_state.value = WeatherComparisonUiState.Failed
			}
		}
	}

	fun reset() {
		comparisonJob?.cancel()
		_state.value = WeatherComparisonUiState.Idle
	}
}

@Composable
internal fun WeatherComparisonSection(settings: AppSettings) {
	var dialogVisible by rememberSaveable { mutableStateOf(false) }

	SectionLabel(stringResource(R.string.weather_compare_title))
	TextButton(onClick = { dialogVisible = true }) {
		Text(stringResource(R.string.weather_compare_open))
	}

	HintText(stringResource(R.string.weather_compare_privacy))
	if (dialogVisible) {
		WeatherComparisonDialog(settings = settings, onDismiss = { dialogVisible = false })
	}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherComparisonDialog(
	settings: AppSettings,
	onDismiss: () -> Unit,
	viewModel: WeatherComparisonViewModel = hiltViewModel()
) {
	val state by viewModel.state.collectAsStateWithLifecycle()
	val context = LocalContext.current
	val shareTitle = stringResource(R.string.weather_compare_share)
	DisposableEffect(viewModel) {
		onDispose { viewModel.reset() }
	}

	var observedSky by rememberSaveable { mutableStateOf(ObservedSky.UNSURE) }
	var expanded by remember { mutableStateOf(false) }
	val dismiss = {
		viewModel.reset()
		onDismiss()
	}

	AlertDialog(
		onDismissRequest = dismiss,
		title = { Text(stringResource(R.string.weather_compare_title)) },
		text = {
			Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
				Text(stringResource(R.string.weather_compare_privacy))
				Text(stringResource(R.string.attribution_met_norway), style = MaterialTheme.typography.bodySmall)
				Text(stringResource(R.string.attribution_met_norway_license), style = MaterialTheme.typography.bodySmall)
				Spacer(modifier = Modifier.height(12.dp))

				Text(stringResource(R.string.weather_compare_observed))
				ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
					OutlinedTextField(
						value = stringResource(observedSky.labelResource),
						onValueChange = {},
						readOnly = true,
						trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
						modifier = Modifier
							.fillMaxWidth()
							.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
					)

					ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
						ObservedSky.entries.forEach { sky ->
							DropdownMenuItem(
								text = { Text(stringResource(sky.labelResource)) },
								onClick = {
									observedSky = sky
									expanded = false
								}
							)
						}
					}
				}

				Spacer(modifier = Modifier.height(12.dp))
				TextButton(
					enabled = state !is WeatherComparisonUiState.Loading,
					onClick = { viewModel.compare(settings) }
				) {
					Text(stringResource(R.string.weather_compare_run))
				}

				when (val current = state) {
					WeatherComparisonUiState.Idle -> Unit
					WeatherComparisonUiState.Loading -> CircularProgressIndicator()
					WeatherComparisonUiState.NoLocation -> Text(stringResource(R.string.weather_compare_no_location))
					WeatherComparisonUiState.Failed -> Text(stringResource(R.string.weather_compare_failed))
					is WeatherComparisonUiState.Ready -> Text(
						text = current.capture.toReport(observedSky, BuildConfig.VERSION_NAME),
						style = MaterialTheme.typography.bodySmall
					)
				}
			}
		},
		confirmButton = {
			TextButton(
				enabled = state is WeatherComparisonUiState.Ready,
				onClick = {
					val ready = state as? WeatherComparisonUiState.Ready ?: return@TextButton
					val intent = Intent(Intent.ACTION_SEND).apply {
						type = "text/plain"
						putExtra(Intent.EXTRA_TEXT, ready.capture.toReport(observedSky, BuildConfig.VERSION_NAME))
					}

					try {
						context.startActivity(Intent.createChooser(intent, shareTitle))
					} catch (_: ActivityNotFoundException) {
						// Without a compatible share target, keep the report visible for review.
					}
				}
			) {
				Text(stringResource(R.string.weather_compare_share))
			}
		},
		dismissButton = {
			TextButton(onClick = dismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}

private val ObservedSky.labelResource: Int
	get() = when (this) {
		ObservedSky.CLEAR -> R.string.weather_compare_clear
		ObservedSky.PARTLY_CLOUDY -> R.string.weather_compare_partly_cloudy
		ObservedSky.OVERCAST -> R.string.weather_compare_overcast
		ObservedSky.RAIN -> R.string.weather_compare_rain
		ObservedSky.SNOW -> R.string.weather_compare_snow
		ObservedSky.UNSURE -> R.string.weather_compare_unsure
	}
