package xyz.attacktive.weatherd.ui.settings

import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.CustomSkyGradient
import xyz.attacktive.weatherd.domain.model.SkyGradientDirection

internal enum class ColorStopIndex {
	START,
	MIDDLE,
	END
}

internal val CustomSkyGradientSaver = Saver<CustomSkyGradient, List<Any>>(
	save = { gradient ->
		listOf(
			gradient.startColor,
			gradient.middleColor,
			gradient.endColor,
			gradient.threeColors,
			gradient.startPosition,
			gradient.middlePosition,
			gradient.endPosition,
			gradient.direction.name
		)
	},
	restore = { list ->
		CustomSkyGradient(
			startColor = list[0] as Int,
			middleColor = list[1] as Int,
			endColor = list[2] as Int,
			threeColors = list[3] as Boolean,
			startPosition = list[4] as Float,
			middlePosition = list[5] as Float,
			endPosition = list[6] as Float,
			direction = SkyGradientDirection.valueOf(list[7] as String)
		)
	}
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomSkyGradientEditor(
	initialGradient: CustomSkyGradient,
	defaultGradient: CustomSkyGradient,
	onApply: (CustomSkyGradient) -> Unit,
	onCancel: () -> Unit
) {
	var draft by rememberSaveable(stateSaver = CustomSkyGradientSaver) {
		mutableStateOf(initialGradient.normalized())
	}
	var editingStop by rememberSaveable { mutableStateOf<ColorStopIndex?>(null) }

	Dialog(
		onDismissRequest = onCancel,
		properties = DialogProperties(
			usePlatformDefaultWidth = false,
			decorFitsSystemWindows = false
		)
	) {
		Scaffold(
			topBar = {
				TopAppBar(
					title = { Text(stringResource(R.string.custom_sky_gradient_title)) },
					navigationIcon = {
						IconButton(onClick = onCancel) {
							Icon(
								Icons.AutoMirrored.Filled.ArrowBack,
								contentDescription = stringResource(R.string.content_description_back)
							)
						}
					},
					actions = {
						TextButton(onClick = { draft = defaultGradient.normalized() }) {
							Text(stringResource(R.string.reset_to_default))
						}
					}
				)
			},
			bottomBar = {
				Surface(
					tonalElevation = 3.dp,
					modifier = Modifier.fillMaxWidth()
				) {
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.navigationBarsPadding()
							.padding(horizontal = 16.dp, vertical = 12.dp),
						horizontalArrangement = Arrangement.spacedBy(12.dp)
					) {
						OutlinedButton(
							onClick = onCancel,
							modifier = Modifier.weight(1f)
						) {
							Text(stringResource(R.string.cancel))
						}

						Button(
							onClick = { onApply(draft.normalized()) },
							modifier = Modifier.weight(1f)
						) {
							Text(stringResource(R.string.apply))
						}
					}
				}
			},
			modifier = Modifier.fillMaxSize()
		) { innerPadding ->
			Column(
				modifier = Modifier
					.fillMaxSize()
					.padding(innerPadding)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 16.dp, vertical = 8.dp)
			) {
				GradientPreviewWithHandles(
					draft = draft,
					onDraftChange = { draft = it },
					onOpenColorPicker = { editingStop = it }
				)

				Spacer(modifier = Modifier.height(16.dp))

				SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
					SegmentedButton(
						selected = !draft.threeColors,
						onClick = { draft = draft.copy(threeColors = false) },
						shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
					) {
						Text(
							text = stringResource(R.string.custom_sky_gradient_two_colors),
							style = MaterialTheme.typography.labelSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					SegmentedButton(
						selected = draft.threeColors,
						onClick = {
							val clampedMiddle = draft.middlePosition.coerceIn(draft.startPosition, draft.endPosition)
							draft = draft.copy(threeColors = true, middlePosition = clampedMiddle)
						},
						shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
					) {
						Text(
							text = stringResource(R.string.custom_sky_gradient_three_colors),
							style = MaterialTheme.typography.labelSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
				}

				Spacer(modifier = Modifier.height(12.dp))

				Text(
					text = stringResource(R.string.custom_sky_gradient_direction),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					modifier = Modifier.padding(bottom = 4.dp)
				)

				SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
					SegmentedButton(
						selected = draft.direction == SkyGradientDirection.VERTICAL,
						onClick = { draft = draft.copy(direction = SkyGradientDirection.VERTICAL) },
						shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
					) {
						Text(
							text = stringResource(R.string.custom_sky_gradient_direction_vertical),
							style = MaterialTheme.typography.labelSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					SegmentedButton(
						selected = draft.direction == SkyGradientDirection.HORIZONTAL,
						onClick = { draft = draft.copy(direction = SkyGradientDirection.HORIZONTAL) },
						shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
					) {
						Text(
							text = stringResource(R.string.custom_sky_gradient_direction_horizontal),
							style = MaterialTheme.typography.labelSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}

					SegmentedButton(
						selected = draft.direction == SkyGradientDirection.DIAGONAL,
						onClick = { draft = draft.copy(direction = SkyGradientDirection.DIAGONAL) },
						shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
					) {
						Text(
							text = stringResource(R.string.custom_sky_gradient_direction_diagonal),
							style = MaterialTheme.typography.labelSmall,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis
						)
					}
				}

				Spacer(modifier = Modifier.height(12.dp))

				OutlinedButton(
					onClick = { draft = draft.reversed() },
					modifier = Modifier.fillMaxWidth()
				) {
					Icon(
						Icons.Default.Refresh,
						contentDescription = null,
						modifier = Modifier.size(18.dp)
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(stringResource(R.string.custom_sky_gradient_reverse_colors))
				}

				Spacer(modifier = Modifier.height(16.dp))

				val startMax = if (draft.threeColors) draft.middlePosition else draft.endPosition
				ColorRow(
					label = stringResource(R.string.custom_sky_gradient_start_color),
					colorInt = draft.startColor,
					position = draft.startPosition,
					minBound = 0f,
					maxBound = startMax,
					positionLabel = stringResource(R.string.custom_sky_gradient_start_position),
					onPositionChange = {
						draft = draft.copy(startPosition = it.coerceIn(0f, startMax))
					},
					onEditClick = { editingStop = ColorStopIndex.START }
				)

				if (draft.threeColors) {
					Spacer(modifier = Modifier.height(8.dp))

					ColorRow(
						label = stringResource(R.string.custom_sky_gradient_middle_color),
						colorInt = draft.middleColor,
						position = draft.middlePosition,
						minBound = draft.startPosition,
						maxBound = draft.endPosition,
						positionLabel = stringResource(R.string.custom_sky_gradient_middle_position),
						onPositionChange = {
							draft = draft.copy(middlePosition = it.coerceIn(draft.startPosition, draft.endPosition))
						},
						onEditClick = { editingStop = ColorStopIndex.MIDDLE }
					)
				}

				Spacer(modifier = Modifier.height(8.dp))

				val endMin = if (draft.threeColors) draft.middlePosition else draft.startPosition
				ColorRow(
					label = stringResource(R.string.custom_sky_gradient_end_color),
					colorInt = draft.endColor,
					position = draft.endPosition,
					minBound = endMin,
					maxBound = 1f,
					positionLabel = stringResource(R.string.custom_sky_gradient_end_position),
					onPositionChange = {
						draft = draft.copy(endPosition = it.coerceIn(endMin, 1f))
					},
					onEditClick = { editingStop = ColorStopIndex.END }
				)

				Spacer(modifier = Modifier.height(16.dp))

				HintText(stringResource(R.string.custom_sky_gradient_adaptation_hint))
			}
		}

		editingStop?.let { stopIndex ->
			val (color, titleRes) = when (stopIndex) {
				ColorStopIndex.START -> draft.startColor to R.string.custom_sky_gradient_start_color
				ColorStopIndex.MIDDLE -> draft.middleColor to R.string.custom_sky_gradient_middle_color
				ColorStopIndex.END -> draft.endColor to R.string.custom_sky_gradient_end_color
			}

			ColorPickerDialog(
				initialColor = color,
				title = stringResource(titleRes),
				onConfirm = { newColor ->
					draft = when (stopIndex) {
						ColorStopIndex.START -> draft.copy(startColor = newColor)
						ColorStopIndex.MIDDLE -> draft.copy(middleColor = newColor)
						ColorStopIndex.END -> draft.copy(endColor = newColor)
					}
					editingStop = null
				},
				onDismiss = { editingStop = null }
			)
		}
	}
}

@Composable
private fun GradientPreviewWithHandles(
	draft: CustomSkyGradient,
	onDraftChange: (CustomSkyGradient) -> Unit,
	onOpenColorPicker: (ColorStopIndex) -> Unit,
	modifier: Modifier = Modifier
) {
	val density = LocalDensity.current
	val handleSizeDp = 32.dp
	val handleSizePx = with(density) { handleSizeDp.toPx() }
	val previewDescription = stringResource(R.string.custom_sky_gradient_preview)

	BoxWithConstraints(
		modifier = modifier
			.fillMaxWidth()
			.height(180.dp)
			.clip(RoundedCornerShape(12.dp))
			.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
			.semantics { contentDescription = previewDescription }
	) {
		val widthPx = constraints.maxWidth.toFloat()
		val heightPx = constraints.maxHeight.toFloat()

		val startColor = Color(0xFF000000.toInt() or draft.startColor)
		val middleColor = Color(0xFF000000.toInt() or draft.middleColor)
		val endColor = Color(0xFF000000.toInt() or draft.endColor)

		val stops = if (draft.threeColors) {
			arrayOf(
				draft.startPosition to startColor,
				draft.middlePosition to middleColor,
				draft.endPosition to endColor
			)
		} else {
			arrayOf(
				draft.startPosition to startColor,
				draft.endPosition to endColor
			)
		}

		val gradientBrush = when (draft.direction) {
			SkyGradientDirection.VERTICAL -> Brush.verticalGradient(colorStops = stops)
			SkyGradientDirection.HORIZONTAL -> Brush.horizontalGradient(colorStops = stops)
			SkyGradientDirection.DIAGONAL -> Brush.linearGradient(
				colorStops = stops,
				start = Offset.Zero,
				end = Offset(widthPx, heightPx)
			)
		}

		Box(
			modifier = Modifier
				.fillMaxSize()
				.background(gradientBrush)
		)

		fun handleCenter(position: Float): Offset {
			val clamped = position.coerceIn(0f, 1f)
			return when (draft.direction) {
				SkyGradientDirection.VERTICAL -> Offset(widthPx / 2f, clamped * heightPx)
				SkyGradientDirection.HORIZONTAL -> Offset(clamped * widthPx, heightPx / 2f)
				SkyGradientDirection.DIAGONAL -> Offset(clamped * widthPx, clamped * heightPx)
			}
		}

		fun projectDelta(delta: Offset) = when (draft.direction) {
			SkyGradientDirection.VERTICAL -> {
				if (heightPx > 0f) {
					delta.y / heightPx
				} else {
					0f
				}
			}

			SkyGradientDirection.HORIZONTAL -> {
				if (widthPx > 0f) {
					delta.x / widthPx
				} else {
					0f
				}
			}

			SkyGradientDirection.DIAGONAL -> {
				val lenSq = widthPx * widthPx + heightPx * heightPx
				if (lenSq > 0f) {
					(delta.x * widthPx + delta.y * heightPx) / lenSq
				} else {
					0f
				}
			}
		}

		val startCenter = handleCenter(draft.startPosition)
		val startLeftDp = with(density) { (startCenter.x - handleSizePx / 2f).coerceIn(0f, widthPx - handleSizePx).toDp() }
		val startTopDp = with(density) { (startCenter.y - handleSizePx / 2f).coerceIn(0f, heightPx - handleSizePx).toDp() }

		StopHandle(
			label = "1",
			color = startColor,
			contentDescription = stringResource(R.string.custom_sky_gradient_preview_handle_start),
			onClick = { onOpenColorPicker(ColorStopIndex.START) },
			onDrag = { dragAmount ->
				val deltaT = projectDelta(dragAmount)
				val maxBound = if (draft.threeColors) draft.middlePosition else draft.endPosition
				val newPosition = (draft.startPosition + deltaT).coerceIn(0f, maxBound)
				onDraftChange(draft.copy(startPosition = newPosition))
			},
			modifier = Modifier
				.offset(x = startLeftDp, y = startTopDp)
				.size(handleSizeDp)
		)

		if (draft.threeColors) {
			val middleCenter = handleCenter(draft.middlePosition)
			val middleLeftDp = with(density) { (middleCenter.x - handleSizePx / 2f).coerceIn(0f, widthPx - handleSizePx).toDp() }
			val middleTopDp = with(density) { (middleCenter.y - handleSizePx / 2f).coerceIn(0f, heightPx - handleSizePx).toDp() }

			StopHandle(
				label = "2",
				color = middleColor,
				contentDescription = stringResource(R.string.custom_sky_gradient_preview_handle_middle),
				onClick = { onOpenColorPicker(ColorStopIndex.MIDDLE) },
				onDrag = { dragAmount ->
					val deltaT = projectDelta(dragAmount)
					val newPosition = (draft.middlePosition + deltaT).coerceIn(draft.startPosition, draft.endPosition)
					onDraftChange(draft.copy(middlePosition = newPosition))
				},
				modifier = Modifier
					.offset(x = middleLeftDp, y = middleTopDp)
					.size(handleSizeDp)
			)
		}

		val endCenter = handleCenter(draft.endPosition)
		val endLeftDp = with(density) { (endCenter.x - handleSizePx / 2f).coerceIn(0f, widthPx - handleSizePx).toDp() }
		val endTopDp = with(density) { (endCenter.y - handleSizePx / 2f).coerceIn(0f, heightPx - handleSizePx).toDp() }

		StopHandle(
			label = if (draft.threeColors) "3" else "2",
			color = endColor,
			contentDescription = stringResource(R.string.custom_sky_gradient_preview_handle_end),
			onClick = { onOpenColorPicker(ColorStopIndex.END) },
			onDrag = { dragAmount ->
				val deltaT = projectDelta(dragAmount)
				val minBound = if (draft.threeColors) draft.middlePosition else draft.startPosition
				val newPosition = (draft.endPosition + deltaT).coerceIn(minBound, 1f)
				onDraftChange(draft.copy(endPosition = newPosition))
			},
			modifier = Modifier
				.offset(x = endLeftDp, y = endTopDp)
				.size(handleSizeDp)
		)
	}
}

@Composable
private fun StopHandle(
	label: String,
	color: Color,
	contentDescription: String,
	onClick: () -> Unit,
	onDrag: (Offset) -> Unit,
	modifier: Modifier = Modifier
) {
	val currentOnDrag by rememberUpdatedState(onDrag)
	val currentOnClick by rememberUpdatedState(onClick)
	val textColor = if (color.luminance() > 0.5f) Color.Black else Color.White

	Box(
		contentAlignment = Alignment.Center,
		modifier = modifier
			.shadow(4.dp, CircleShape)
			.clip(CircleShape)
			.background(color)
			.border(2.dp, Color.White, CircleShape)
			.clickable(onClick = { currentOnClick() })
			.pointerInput(Unit) {
				detectDragGestures { change, dragAmount ->
					change.consume()
					currentOnDrag(dragAmount)
				}
			}
			.semantics { this.contentDescription = contentDescription }
	) {
		Text(
			text = label,
			color = textColor,
			style = MaterialTheme.typography.labelSmall
		)
	}
}

@Composable
private fun ColorRow(
	label: String,
	colorInt: Int,
	position: Float,
	minBound: Float,
	maxBound: Float,
	positionLabel: String,
	onPositionChange: (Float) -> Unit,
	onEditClick: () -> Unit,
	modifier: Modifier = Modifier
) {
	val opaqueColor = Color(0xFF000000.toInt() or colorInt)
	val hexString = String.format("#%06X", colorInt and 0x00FFFFFF)
	var showPositionDialog by rememberSaveable { mutableStateOf(false) }

	Column(modifier = modifier.fillMaxWidth()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(8.dp))
				.clickable(onClick = onEditClick)
				.padding(vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Box(
				modifier = Modifier
					.size(40.dp)
					.clip(RoundedCornerShape(8.dp))
					.background(opaqueColor)
					.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
			)

			Spacer(modifier = Modifier.width(12.dp))

			Column(modifier = Modifier.weight(1f)) {
				Text(text = label, style = MaterialTheme.typography.bodyMedium)
				Text(
					text = hexString,
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant
				)
			}

			TextButton(onClick = onEditClick) {
				Text(stringResource(R.string.custom_sky_gradient_edit_color))
			}
		}

		Spacer(modifier = Modifier.height(4.dp))

		Row(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(4.dp))
				.clickable { showPositionDialog = true }
				.padding(vertical = 2.dp),
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(
				text = "$positionLabel · ${(position * 100).roundToInt()}%",
				style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant,
				modifier = Modifier.weight(1f)
			)

			TextButton(onClick = { showPositionDialog = true }) {
				Text(
					text = "${(position * 100).roundToInt()}%",
					style = MaterialTheme.typography.labelMedium
				)
			}
		}

		Slider(
			value = position,
			onValueChange = { onPositionChange(it.coerceIn(minBound, maxBound)) },
			valueRange = 0f..1f,
			modifier = Modifier
				.fillMaxWidth()
				.semantics { contentDescription = positionLabel }
		)
	}

	if (showPositionDialog) {
		PositionPercentDialog(
			title = positionLabel,
			currentPosition = position,
			minBound = minBound,
			maxBound = maxBound,
			onConfirm = {
				onPositionChange(it)
				showPositionDialog = false
			},
			onDismiss = { showPositionDialog = false }
		)
	}
}

@Composable
private fun PositionPercentDialog(
	title: String,
	currentPosition: Float,
	minBound: Float,
	maxBound: Float,
	onConfirm: (Float) -> Unit,
	onDismiss: () -> Unit
) {
	val currentPercent = (currentPosition * 100).roundToInt()
	var textValue by rememberSaveable { mutableStateOf(currentPercent.toString()) }
	val minPercent = (minBound * 100).roundToInt()
	val maxPercent = (maxBound * 100).roundToInt()

	val parsed = textValue.trim().toIntOrNull()
	val isValid = parsed != null && parsed in minPercent..maxPercent && parsed in 0..100

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(modifier = Modifier.fillMaxWidth()) {
				OutlinedTextField(
					value = textValue,
					onValueChange = { textValue = it },
					label = { Text("0–100%") },
					supportingText = {
						Text("$minPercent% – $maxPercent%")
					},
					isError = !isValid,
					singleLine = true,
					modifier = Modifier.fillMaxWidth()
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					if (isValid) {
						onConfirm((parsed / 100f).coerceIn(minBound, maxBound))
					}
				},
				enabled = isValid
			) {
				Text(stringResource(R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}

@Composable
private fun ColorPickerDialog(
	initialColor: Int,
	title: String,
	onConfirm: (Int) -> Unit,
	onDismiss: () -> Unit
) {
	val initialOpaque = (0xFF shl 24) or (initialColor and 0x00FFFFFF)
	var red by rememberSaveable { mutableIntStateOf((initialOpaque shr 16) and 0xFF) }
	var green by rememberSaveable { mutableIntStateOf((initialOpaque shr 8) and 0xFF) }
	var blue by rememberSaveable { mutableIntStateOf(initialOpaque and 0xFF) }
	var hexText by rememberSaveable { mutableStateOf(String.format("%02X%02X%02X", red, green, blue)) }
	var hexError by rememberSaveable { mutableStateOf(false) }

	val currentColor = Color(red = red, green = green, blue = blue, alpha = 255)

	AlertDialog(
		onDismissRequest = onDismiss,
		title = { Text(title) },
		text = {
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())
			) {
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.height(56.dp)
						.clip(RoundedCornerShape(8.dp))
						.background(currentColor)
						.border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
				)

				Spacer(modifier = Modifier.height(16.dp))

				OutlinedTextField(
					value = hexText,
					onValueChange = { input ->
						hexText = input
						val cleaned = input.trim().removePrefix("#")
						if (cleaned.length == 6 && cleaned.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
							hexError = false
							red = cleaned.substring(0, 2).toInt(16)
							green = cleaned.substring(2, 4).toInt(16)
							blue = cleaned.substring(4, 6).toInt(16)
						} else {
							hexError = true
						}
					},
					label = { Text(stringResource(R.string.custom_sky_gradient_hex_color)) },
					isError = hexError,
					supportingText = {
						if (hexError) {
							Text(stringResource(R.string.custom_sky_gradient_invalid_hex))
						}
					},
					singleLine = true,
					modifier = Modifier.fillMaxWidth()
				)

				Spacer(modifier = Modifier.height(12.dp))

				Text(
					text = "${stringResource(R.string.custom_sky_gradient_red)}: $red",
					style = MaterialTheme.typography.bodyMedium
				)
				Slider(
					value = red.toFloat(),
					onValueChange = {
						red = it.roundToInt().coerceIn(0, 255)
						hexText = String.format("%02X%02X%02X", red, green, blue)
						hexError = false
					},
					valueRange = 0f..255f,
					modifier = Modifier.fillMaxWidth()
				)

				Text(
					text = "${stringResource(R.string.custom_sky_gradient_green)}: $green",
					style = MaterialTheme.typography.bodyMedium
				)
				Slider(
					value = green.toFloat(),
					onValueChange = {
						green = it.roundToInt().coerceIn(0, 255)
						hexText = String.format("%02X%02X%02X", red, green, blue)
						hexError = false
					},
					valueRange = 0f..255f,
					modifier = Modifier.fillMaxWidth()
				)

				Text(
					text = "${stringResource(R.string.custom_sky_gradient_blue)}: $blue",
					style = MaterialTheme.typography.bodyMedium
				)
				Slider(
					value = blue.toFloat(),
					onValueChange = {
						blue = it.roundToInt().coerceIn(0, 255)
						hexText = String.format("%02X%02X%02X", red, green, blue)
						hexError = false
					},
					valueRange = 0f..255f,
					modifier = Modifier.fillMaxWidth()
				)
			}
		},
		confirmButton = {
			TextButton(
				onClick = {
					val colorInt = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
					onConfirm(colorInt)
				},
				enabled = !hexError && hexText.trim().removePrefix("#").length == 6
			) {
				Text(stringResource(R.string.ok))
			}
		},
		dismissButton = {
			TextButton(onClick = onDismiss) {
				Text(stringResource(R.string.cancel))
			}
		}
	)
}
