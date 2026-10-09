package xyz.attacktive.weatherd.ui.home

import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.debugToolsEnabled
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.render.SCENE_PRESETS
import xyz.attacktive.weatherd.domain.render.SceneRenderer
import xyz.attacktive.weatherd.domain.render.backdropSignature
import xyz.attacktive.weatherd.domain.render.lensFlareMotionActive
import xyz.attacktive.weatherd.domain.render.renderImmutableBitmap
import xyz.attacktive.weatherd.domain.render.sceneAnimationTimeSeconds
import xyz.attacktive.weatherd.service.WeatherLiveWallpaperService

/**
 * A live preview of the current scene — the same renderer and weather source the wallpaper uses, including the persisted scene-simulator override.
 * Refreshes weather on resume, reacts to shared scene-input changes immediately, and advances clock-derived lighting once a second.
 * Honors the user's frame-rate cap, so the preview animates exactly as choppily as the wallpaper it is previewing.
 */
@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
	val context = LocalContext.current
	val renderer = remember { SceneRenderer(context.resources) }
	val motionSensor = viewModel.lensFlareMotionSensor
	val motionOwner = remember { Any() }
	var timeSeconds by remember { mutableFloatStateOf(0f) }
	val sceneState by viewModel.sceneState.collectAsStateWithLifecycle()
	val settings = sceneState.settings
	val debugEnabled = sceneState.simulatorActive
	val debugSceneIndex = settings.sceneSimulatorPresetIndex.coerceIn(0, SCENE_PRESETS.lastIndex)
	val debugPhaseIndex = settings.sceneSimulatorDayPhase.ordinal
	var debugCelestialProgress by remember(settings.sceneSimulatorCelestialProgress) {
		mutableFloatStateOf(settings.sceneSimulatorCelestialProgress.coerceIn(0f, 1f))
	}

	var clockSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }

	var controlsVisible by remember { mutableStateOf(true) }
	val previewInteraction = remember { MutableInteractionSource() }
	val sceneSimulatorVisible = debugToolsEnabled || settings.sceneSimulatorEnabled

	// Read inside the frame loop, which is launched once and has to see a cap the user changes while it runs.
	val currentCap = rememberUpdatedState(settings.frameRateCap)

	val params = remember(sceneState, clockSeconds, debugCelestialProgress) {
		val sharedParams = viewModel.currentParams(sceneState)
		if (debugEnabled && debugCelestialProgress != sharedParams.celestialProgress) {
			// Only an unfinished slider gesture is local; committing it updates both surfaces through the shared provider.
			sharedParams.copy(celestialProgress = debugCelestialProgress)
		} else {
			sharedParams
		}
	}

	val motionActive = lensFlareMotionActive(params)
	LifecycleResumeEffect(motionSensor, motionActive) {
		motionSensor.setActive(motionOwner, motionActive)
		onPauseOrDispose { motionSensor.setActive(motionOwner, false) }
	}

	LifecycleResumeEffect(Unit) {
		viewModel.refresh()
		onPauseOrDispose { }
	}

	LaunchedEffect(renderer) {
		withContext(Dispatchers.Default) {
			renderer.prewarmCloudTextures()
		}
	}

	LaunchedEffect(Unit) {
		while (true) {
			withFrameNanos { frameNanos ->
				motionSensor.advance(frameNanos)
				timeSeconds = sceneAnimationTimeSeconds(frameNanos)
			}

			// Waiting before asking for the next frame is what idles the frame clock; gating the draw alone would still wake the compositor every vsync.
			val intervalMillis = currentCap.value.intervalMillis
			if (intervalMillis > 0L) {
				delay(intervalMillis.milliseconds)
			}
		}
	}

	LaunchedEffect(sceneSimulatorVisible, settings.sceneSimulatorActive) {
		if (!sceneSimulatorVisible && settings.sceneSimulatorActive) {
			viewModel.setSceneSimulatorActive(false)
		}
	}

	LaunchedEffect(viewModel) {
		while (true) {
			clockSeconds = System.currentTimeMillis() / 1000L
			delay(1000.milliseconds)
		}
	}

	BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
		val density = LocalDensity.current
		val widthPx = with(density) { maxWidth.roundToPx() }
		val heightPx = with(density) { maxHeight.roundToPx() }

		// The backdrop cannot show the moon or the sun's arc, so keying on the whole params would rebuild it for foreground-only celestial motion.
		// Photo availability matters because a missing or corrupt stored photo falls back to the procedural dawn sky, whose late-dawn blue progression belongs in the cache key.
		val backgroundPhotoAvailable = viewModel.hasPhotoBackground(params.backdropScene, params.dayPhase)
		val backdropParams = backdropSignature(params, backgroundPhotoAvailable)

		/*
		 * The photo is decoded, lent to the renderer, rasterized into the backdrop and released inside this one block, all on the thread that composes and draws.
		 * SceneRenderer.backgroundPhoto is an unsynchronized field the sky pass dereferences mid-blit, so a LaunchedEffect or an IO dispatcher here would race the draw and could recycle the bitmap under it.
		 * Keying on the backdrop signature rather than recomposition also keeps that decode to once per backdrop change, which is exactly what the wallpaper pays.
		 * backdropParams is the cache key and only that: what is handed to the renderer is the unflattened params, exactly as the wallpaper hands them over, so a backdrop element that comes to read celestialProgress or moonPhase later cannot render at zero here while the wallpaper draws it properly.
		 */
		val backdrop = remember(widthPx, heightPx, backdropParams) {
			val photo = viewModel.loadPhotoBackground(params.backdropScene, params.dayPhase)
			renderer.backgroundPhoto = photo

			try {
				renderImmutableBitmap(widthPx, heightPx) { canvas ->
					renderer.renderBackdrop(canvas, widthPx, heightPx, params)
				}
			} finally {
				renderer.backgroundPhoto = null
				photo?.recycle()
			}
		}

		Canvas(
			modifier = Modifier
				.fillMaxSize()
				.clickable(
					interactionSource = previewInteraction,
					indication = null,
					onClick = { controlsVisible = !controlsVisible }
				)
		) {
			drawIntoCanvas { canvas ->
				canvas.nativeCanvas.drawBitmap(backdrop, 0f, 0f, null)
				renderer.lensFlareOffsetX = motionSensor.offsetX
				renderer.lensFlareOffsetY = motionSensor.offsetY
				renderer.renderForeground(canvas.nativeCanvas, widthPx, heightPx, params, timeSeconds)
			}
		}

		IconButton(
			onClick = onNavigateToSettings,
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(top = 40.dp, end = 4.dp)
		) {
			Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.content_description_settings), tint = Color.White)
		}

		if (controlsVisible) {
			Column(
				modifier = Modifier
					.align(Alignment.BottomCenter)
					.padding(horizontal = 16.dp, vertical = 24.dp),
				horizontalAlignment = Alignment.CenterHorizontally,
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				if (sceneSimulatorVisible) {
					SceneSimulatorControls(
						debugEnabled = debugEnabled,
						sceneLabel = SCENE_PRESETS[debugSceneIndex].name,
						phaseLabel = DayPhase.entries[debugPhaseIndex].name,
						celestialProgress = debugCelestialProgress,
						onDebugToggle = viewModel::toggleSceneSimulator,
						onScenePrevious = {
							viewModel.changeSceneSimulatorPreset(-1)
						},
						onSceneNext = {
							viewModel.changeSceneSimulatorPreset(1)
						},
						onPhasePrevious = {
							viewModel.changeSceneSimulatorDayPhase(-1)
						},
						onPhaseNext = {
							viewModel.changeSceneSimulatorDayPhase(1)
						},
						onCelestialProgressChange = { debugCelestialProgress = it },
						onCelestialProgressChangeFinished = {
							viewModel.setSceneSimulatorCelestialProgress(debugCelestialProgress)
						}
					)
				}

				Button(onClick = { setLiveWallpaper(context) }) {
					Text(stringResource(R.string.set_as_live_wallpaper))
				}
			}
		}
	}
}

@Composable
private fun SceneSimulatorControls(
	debugEnabled: Boolean,
	sceneLabel: String,
	phaseLabel: String,
	celestialProgress: Float,
	onDebugToggle: () -> Unit,
	onScenePrevious: () -> Unit,
	onSceneNext: () -> Unit,
	onPhasePrevious: () -> Unit,
	onPhaseNext: () -> Unit,
	onCelestialProgressChange: (Float) -> Unit,
	onCelestialProgressChangeFinished: () -> Unit
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.background(Color.Black.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
			.padding(horizontal = 12.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp)
	) {
		Row(
			modifier = Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically
		) {
			Text(stringResource(R.string.scene_preview), color = Color.White, style = MaterialTheme.typography.labelLarge)
			TextButton(onClick = onDebugToggle) {
				val text = if (debugEnabled) {
					stringResource(R.string.scene_mode_live)
				} else {
					stringResource(R.string.scene_mode_simulated)
				}

				Text(text, color = Color.White)
			}
		}

		if (debugEnabled) {
			DebugCycleRow(label = sceneLabel, onPrevious = onScenePrevious, onNext = onSceneNext)
			DebugCycleRow(label = phaseLabel, onPrevious = onPhasePrevious, onNext = onPhaseNext)
			Text(
				stringResource(R.string.scene_phase_progress, (celestialProgress * 100f).roundToInt()),
				color = Color.White,
				style = MaterialTheme.typography.labelMedium
			)
			Slider(
				value = celestialProgress,
				onValueChange = onCelestialProgressChange,
				onValueChangeFinished = onCelestialProgressChangeFinished,
				valueRange = 0f..1f
			)
		}
	}
}

@Composable
private fun DebugCycleRow(label: String, onPrevious: () -> Unit, onNext: () -> Unit) {
	Row(
		modifier = Modifier.fillMaxWidth(),
		horizontalArrangement = Arrangement.SpaceBetween,
		verticalAlignment = Alignment.CenterVertically
	) {
		TextButton(onClick = onPrevious) { Text("<", color = Color.White) }
		Text(label, color = Color.White, fontFamily = FontFamily.Monospace)
		TextButton(onClick = onNext) { Text(">", color = Color.White) }
	}
}

private fun setLiveWallpaper(context: Context) {
	val intents = listOf(
		Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
			.putExtra(
				WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
				ComponentName(context, WeatherLiveWallpaperService::class.java)
			),
		Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)
	)

	for (intent in intents) {
		try {
			context.startActivity(intent)
			return
		} catch (_: ActivityNotFoundException) {
			// Fall through to the next intent.
		} catch (_: SecurityException) {
			// Fall through to the next intent.
		}
	}

	Toast.makeText(context, context.getString(R.string.wallpaper_chooser_unavailable), Toast.LENGTH_SHORT)
		.show()
}
