package xyz.attacktive.weatherd.ui.home

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import xyz.attacktive.weatherd.debugToolsEnabled
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.render.SCENE_PRESETS
import xyz.attacktive.weatherd.domain.render.SceneRenderer
import xyz.attacktive.weatherd.domain.render.debugSceneParams
import xyz.attacktive.weatherd.service.WeatherLiveWallpaperService
import android.graphics.Canvas as AndroidCanvas

/**
 * A live preview of the current scene — the same renderer the wallpaper uses, fed the real weather via
 * [HomeViewModel]. Refreshes on resume so returning from Settings (e.g. after changing the city) reflects
 * the new scene, and re-reads the params once a second so the weather loading in and day-phase changes show.
 */
@Composable
fun HomeScreen(onNavigateToSettings: () -> Unit, viewModel: HomeViewModel = hiltViewModel()) {
	val context = LocalContext.current
	val renderer = remember { SceneRenderer() }
	var timeSeconds by remember { mutableFloatStateOf(0f) }
	var liveParams by remember { mutableStateOf(viewModel.currentParams()) }
	var debugEnabled by remember { mutableStateOf(false) }
	var debugSceneIndex by remember { mutableIntStateOf(0) }
	var debugPhaseIndex by remember { mutableIntStateOf(DayPhase.DAY.ordinal) }

	val params = if (debugEnabled) {
		debugSceneParams(SCENE_PRESETS[debugSceneIndex], DayPhase.entries[debugPhaseIndex])
	} else {
		liveParams
	}

	LifecycleResumeEffect(Unit) {
		viewModel.refresh()
		onPauseOrDispose { }
	}

	LaunchedEffect(Unit) {
		val startNanos = withFrameNanos { it }

		while (true) {
			withFrameNanos { frameNanos ->
				timeSeconds = (frameNanos - startNanos) / 1_000_000_000f
			}
		}
	}

	LaunchedEffect(debugEnabled) {
		while (!debugEnabled) {
			liveParams = viewModel.currentParams()
			delay(1000.milliseconds)
		}
	}

	BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
		val density = LocalDensity.current
		val widthPx = with(density) { maxWidth.roundToPx() }
		val heightPx = with(density) { maxHeight.roundToPx() }
		val backdrop = remember(widthPx, heightPx, params) {
			createBitmap(widthPx, heightPx)
				.also { renderer.renderBackdrop(AndroidCanvas(it), widthPx, heightPx, params) }
		}

		Canvas(modifier = Modifier.fillMaxSize()) {
			drawIntoCanvas { canvas ->
				canvas.nativeCanvas.drawBitmap(backdrop, 0f, 0f, null)
				renderer.renderForeground(canvas.nativeCanvas, widthPx, heightPx, params, timeSeconds)
			}
		}

		IconButton(
			onClick = onNavigateToSettings,
			modifier = Modifier
				.align(Alignment.TopEnd)
				.padding(top = 40.dp, end = 4.dp)
		) {
			Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
		}

		Column(
			modifier = Modifier
				.align(Alignment.BottomCenter)
				.padding(horizontal = 16.dp, vertical = 24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(8.dp)
		) {
			if (debugToolsEnabled) {
				DebugSceneControls(
					debugEnabled = debugEnabled,
					sceneLabel = SCENE_PRESETS[debugSceneIndex].name,
					phaseLabel = DayPhase.entries[debugPhaseIndex].name,
					onDebugEnabledChange = { debugEnabled = it },
					onScenePrevious = {
						debugSceneIndex = (debugSceneIndex + SCENE_PRESETS.size - 1) % SCENE_PRESETS.size
					},
					onSceneNext = {
						debugSceneIndex = (debugSceneIndex + 1) % SCENE_PRESETS.size
					},
					onPhasePrevious = {
						debugPhaseIndex = (debugPhaseIndex + DayPhase.entries.size - 1) % DayPhase.entries.size
					},
					onPhaseNext = {
						debugPhaseIndex = (debugPhaseIndex + 1) % DayPhase.entries.size
					}
				)
			}

			Button(onClick = { context.startActivity(liveWallpaperIntent(context)) }) {
				Text("Set as live wallpaper")
			}
		}
	}
}

@Composable
private fun DebugSceneControls(
	debugEnabled: Boolean,
	sceneLabel: String,
	phaseLabel: String,
	onDebugEnabledChange: (Boolean) -> Unit,
	onScenePrevious: () -> Unit,
	onSceneNext: () -> Unit,
	onPhasePrevious: () -> Unit,
	onPhaseNext: () -> Unit
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
			Text("Scene preview", color = Color.White, style = MaterialTheme.typography.labelLarge)
			TextButton(onClick = { onDebugEnabledChange(!debugEnabled) }) {
				Text(if (debugEnabled) "Live" else "Debug", color = Color.White)
			}
		}

		if (debugEnabled) {
			DebugCycleRow(label = sceneLabel, onPrevious = onScenePrevious, onNext = onSceneNext)
			DebugCycleRow(label = phaseLabel, onPrevious = onPhasePrevious, onNext = onPhaseNext)
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

private fun liveWallpaperIntent(context: Context) = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
	.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(context, WeatherLiveWallpaperService::class.java))
