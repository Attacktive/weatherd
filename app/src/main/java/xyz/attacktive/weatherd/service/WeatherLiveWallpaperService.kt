package xyz.attacktive.weatherd.service

import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import dagger.hilt.android.AndroidEntryPoint
import xyz.attacktive.weatherd.domain.model.AnimationIdleTimeout
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.model.TreeSeason
import xyz.attacktive.weatherd.domain.model.TreeStyle
import xyz.attacktive.weatherd.domain.render.SceneAnimationRestartController
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.SceneRenderer
import xyz.attacktive.weatherd.domain.render.SceneScreenEffects
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.render.WeatherSceneState
import xyz.attacktive.weatherd.domain.render.backdropSignature
import xyz.attacktive.weatherd.domain.render.holidayCalendarRefreshDelayMillis
import xyz.attacktive.weatherd.domain.render.lensFlareMotionActive
import xyz.attacktive.weatherd.domain.render.renderImmutableBitmap
import xyz.attacktive.weatherd.domain.render.refreshWhileVisible
import xyz.attacktive.weatherd.domain.render.sceneAnimationTimeSeconds
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository
import xyz.attacktive.weatherd.platform.LensFlareMotionSensor
import xyz.attacktive.weatherd.platform.currentHomeLauncher
import xyz.attacktive.weatherd.platform.wallpaperScrollingSupportedBy

/**
 * Live wallpaper that animates the shared weather scene.
 * The scene comes from [WeatherSceneProvider] (shared with the rest of the app): live weather is fetched when the wallpaper becomes visible and cached, while an active scene-simulator override replaces it immediately from persisted settings.
 * The static backdrop is cached and only rebuilt when the scene actually changes — a weather refresh or a dawn/day/dusk/night flip; the animated foreground is redrawn on every vsync the user's frame-rate cap allows and gated on visibility, so it costs nothing while the screen is off or covered.
 * An optional idle timeout freezes continuous foreground motion after the wallpaper has stayed visible for the selected period, then wakes only for scene changes, launcher offsets, and a low-frequency clock refresh until visibility or an explicit widget refresh restarts the motion window.
 * Scene flips crossfade briefly instead of swapping in one frame while motion is active; settled scenes update directly so a frozen animation clock cannot strand a crossfade.
 */
@AndroidEntryPoint
class WeatherLiveWallpaperService: WallpaperService() {
	@Inject lateinit var sceneProvider: WeatherSceneProvider
	@Inject lateinit var settingsRepository: SettingsRepository
	@Inject lateinit var photoBackgroundRepository: PhotoBackgroundRepository
	@Inject lateinit var lensFlareMotionSensor: LensFlareMotionSensor
	@Inject lateinit var sceneAnimationRestartController: SceneAnimationRestartController

	override fun onCreateEngine(): Engine = SceneEngine()

	private inner class SceneEngine: Engine(), Choreographer.FrameCallback {
		private val renderer = SceneRenderer(resources)
		private val screenEffects = SceneScreenEffects(renderer)
		private val choreographer = Choreographer.getInstance()
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
		private var weatherRefreshJob: Job? = null
		private var moonPreparationJob: Job? = null
		private var requestedMoonPhase: Float? = null
		private var treePreparationJob: Job? = null
		private var requestedTreeStyle: TreeStyle? = null
		private var requestedTreeSeason: TreeSeason? = null

		private var backdrop: Bitmap? = null
		private var renderedParams: SceneParams? = null
		private var previousBackdrop: Bitmap? = null
		private var fadeStartSeconds = 0f
		private var activeParams: SceneParams? = null
		private var activeSceneState: WeatherSceneState? = null
		private var paramsComputedAtSecond = 0L
		private var width = 0
		private var height = 0
		private var reflectionMotionActive = false
		private var motionStartedAtNanos = 0L
		private var frozenAnimationTimeSeconds: Float? = null
		private var frozenCelebrationEpochMillis: Long? = null
		@Volatile private var visible = false
		@Volatile private var frameRateCap = FrameRateCap.UNCAPPED
		@Volatile private var animationIdleTimeout = AnimationIdleTimeout.NEVER
		@Volatile private var wallpaperScrollingPreferenceEnabled = false
		@Volatile private var wallpaperScrollingSupported = wallpaperScrollingSupportedBy(currentHomeLauncher(this@WeatherLiveWallpaperService)?.packageName)
		@Volatile private var wallpaperOffsetX = 0.5f

		init {
			scope.launch(Dispatchers.Default) {
				renderer.prewarmCloudTextures()
				withContext(Dispatchers.Main.immediate) {
					requestImmediateFrame()
				}
			}

			scope.launch(Dispatchers.Main.immediate) {
				settingsRepository.settings.collect {
					val previousIdleTimeout = animationIdleTimeout
					frameRateCap = it.frameRateCap
					animationIdleTimeout = it.animationIdleTimeout
					wallpaperScrollingPreferenceEnabled = it.wallpaperScrollingEnabled
					if (!renderer.isSceneryTextureReady(it.backdropScene)) {
						withContext(Dispatchers.Default) {
							renderer.prewarmSceneryTexture(it.backdropScene)
						}

						requestImmediateFrame()
					}

					if (!renderer.isSunTextureReady(it.sunStyle)) {
						withContext(Dispatchers.Default) {
							renderer.prewarmSunTexture(it.sunStyle)
						}

						requestImmediateFrame()
					}

					if (visible) {
						if (previousIdleTimeout != animationIdleTimeout) {
							requestImmediateFrame()
						}

						scope.launch { sceneProvider.refresh(nowEpochSeconds()) }
					}
				}
			}

			scope.launch(Dispatchers.Main.immediate) {
				sceneProvider.sceneState.drop(1).collect {
					if (visible && !wallpaperAnimationActive(animationIdleTimeout, motionStartedAtNanos, System.nanoTime())) {
						requestImmediateFrame()
					}
				}
			}

			scope.launch(Dispatchers.Main.immediate) {
				sceneAnimationRestartController.restarts.collect {
					restartAnimationWindow()
				}
			}
		}

		override fun onVisibilityChanged(visible: Boolean) {
			this.visible = visible
			weatherRefreshJob?.cancel()
			choreographer.removeFrameCallback(this)
			if (!visible) {
				frozenAnimationTimeSeconds = null
				frozenCelebrationEpochMillis = null
				updateReflectionMotion(false)

				return
			}

			wallpaperScrollingSupported = wallpaperScrollingSupportedBy(currentHomeLauncher(this@WeatherLiveWallpaperService)?.packageName)
			weatherRefreshJob = scope.launch { sceneProvider.refreshWhileVisible() }
			restartAnimationWindow()
		}

		override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xOffsetStep: Float, yOffsetStep: Float, xPixelOffset: Int, yPixelOffset: Int) {
			wallpaperOffsetX = normalizeWallpaperOffset(xOffset)
			val scrollingActive = wallpaperScrollingPreferenceEnabled && wallpaperScrollingSupported
			if (scrollingActive && visible && !wallpaperAnimationActive(animationIdleTimeout, motionStartedAtNanos, System.nanoTime())) {
				requestImmediateFrame()
			}
		}

		override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
			this.width = width
			this.height = height
			backdrop = null
			renderedParams = null
			previousBackdrop = null

			if (visible) {
				requestImmediateFrame()
			}
		}

		override fun onSurfaceDestroyed(holder: SurfaceHolder) {
			choreographer.removeFrameCallback(this)
			width = 0
			height = 0
			updateReflectionMotion(false)
		}

		override fun onDestroy() {
			choreographer.removeFrameCallback(this)
			updateReflectionMotion(false)
			scope.cancel()
		}

		override fun doFrame(frameTimeNanos: Long) {
			if (!visible) {
				return
			}

			val calendarEpochMillis = System.currentTimeMillis()
			val motionActive = wallpaperAnimationActive(animationIdleTimeout, motionStartedAtNanos, frameTimeNanos)
			val timeSeconds = if (motionActive) {
				frozenAnimationTimeSeconds = null
				sceneAnimationTimeSeconds(frameTimeNanos)
			} else {
				frozenAnimationTimeSeconds ?: sceneAnimationTimeSeconds(frameTimeNanos).also { frozenAnimationTimeSeconds = it }
			}
			val celebrationEpochMillis = if (motionActive) {
				frozenCelebrationEpochMillis = null
				calendarEpochMillis
			} else {
				frozenCelebrationEpochMillis ?: calendarEpochMillis.also { frozenCelebrationEpochMillis = it }
			}

			// Use the shared monotonic frame clock while motion is active, then hold its final phase while the wallpaper is settled.
			drawFrame(frameTimeNanos, timeSeconds, celebrationEpochMillis, motionActive)
			scheduleNextFrame(motionActive, frameTimeNanos, calendarEpochMillis)
		}

		private fun requestImmediateFrame() {
			if (!visible) {
				return
			}

			choreographer.removeFrameCallback(this)
			choreographer.postFrameCallback(this)
		}

		private fun restartAnimationWindow() {
			if (!visible) {
				return
			}

			motionStartedAtNanos = System.nanoTime()
			frozenAnimationTimeSeconds = null
			frozenCelebrationEpochMillis = null
			requestImmediateFrame()
		}

		/** Prewarm the resolved season after location or calendar changes, not the stored Auto selection. */
		private fun prepareTrees(params: SceneParams) {
			if (!params.treesEnabled || renderer.isTreeTextureReady(params.treeStyle, params.treeSeason)) {
				return
			}

			if (requestedTreeStyle == params.treeStyle && requestedTreeSeason == params.treeSeason && treePreparationJob?.isActive == true) {
				return
			}

			requestedTreeStyle = params.treeStyle
			requestedTreeSeason = params.treeSeason
			treePreparationJob?.cancel()
			treePreparationJob = scope.launch(Dispatchers.Default) {
				renderer.prewarmTreeTextures(params.treeStyle, params.treeSeason)
				withContext(Dispatchers.Main.immediate) {
					requestImmediateFrame()
				}
			}
		}

		/** A missing phase queues background work once; the frame itself only reads prepared sprites. */
		private fun prepareMoonPhase(params: SceneParams) {
			if (params.dayPhase != DayPhase.NIGHT || !params.moonVisible) {
				return
			}

			val phase = params.moonPhase
			if (renderer.isMoonPhaseReady(phase)) {
				return
			}

			if (requestedMoonPhase == phase && moonPreparationJob?.isActive == true) {
				return
			}

			requestedMoonPhase = phase
			moonPreparationJob?.cancel()
			moonPreparationJob = scope.launch(Dispatchers.Default) {
				renderer.prewarmMoonPhase(phase)
				withContext(Dispatchers.Main.immediate) {
					requestImmediateFrame()
				}
			}
		}

		/** Asks for the next animation frame at the configured cap, or only a low-frequency clock refresh after motion settles. */
		private fun scheduleNextFrame(motionActive: Boolean, frameTimeNanos: Long, calendarEpochMillis: Long) {
			if (!motionActive) {
				val elapsedDrawWallMillis = System.currentTimeMillis() - calendarEpochMillis
				val targetDelay = holidayCalendarRefreshDelayMillis(calendarEpochMillis, SETTLED_SCENE_REFRESH_MILLIS)
				val delayMillis = (targetDelay - elapsedDrawWallMillis).coerceAtLeast(0L)
				choreographer.postFrameCallbackDelayed(this, delayMillis)

				return
			}

			val delayMillis = frameRateCap.remainingDelayMillis(frameTimeNanos, System.nanoTime())
			if (delayMillis > 0L) {
				choreographer.postFrameCallbackDelayed(this, delayMillis)
			} else {
				choreographer.postFrameCallback(this)
			}
		}

		private fun drawFrame(frameTimeNanos: Long, timeSeconds: Float, celebrationEpochMillis: Long, motionActive: Boolean) {
			if (width == 0 || height == 0) {
				return
			}

			val params = currentParams()
			prepareTrees(params)
			prepareMoonPhase(params)
			updateAnimatedMotion(params, frameTimeNanos, motionActive)

			val wallpaperScrollingEnabled = wallpaperScrollingPreferenceEnabled && wallpaperScrollingSupported
			val sceneWidth = wallpaperSceneWidth(width, wallpaperScrollingEnabled)
			val outgoing = backdrop
			val current = backdropFor(params, sceneWidth)
			updateBackdropTransition(outgoing, current, timeSeconds, motionActive)

			val holder = surfaceHolder
			var canvas: Canvas? = null

			try {
				/*
				 * GPU-composited canvas: bitmap blits and primitives are cheap there, where a software canvas at full resolution can't hold 60fps.
				 * Falls back if the surface refuses.
				 */
				canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: holder.lockCanvas()
				if (canvas != null) {
					val viewportLeft = wallpaperViewportLeft(width, current.width, wallpaperOffsetX, wallpaperScrollingEnabled)
					drawScene(canvas, current, params, timeSeconds, celebrationEpochMillis, viewportLeft)
				}
			} finally {
				if (canvas != null) {
					holder.unlockCanvasAndPost(canvas)
				}
			}
		}

		private fun updateAnimatedMotion(params: SceneParams, frameTimeNanos: Long, motionActive: Boolean) {
			updateReflectionMotion(motionActive && lensFlareMotionActive(params))
			if (!motionActive) {
				return
			}

			lensFlareMotionSensor.advance(frameTimeNanos)
		}

		private fun updateBackdropTransition(outgoing: Bitmap?, current: Bitmap, timeSeconds: Float, motionActive: Boolean) {
			if (!motionActive) {
				previousBackdrop = null

				return
			}

			if (outgoing != null && current !== outgoing && outgoing.width == current.width) {
				// The scene flipped: keep the outgoing backdrop around and ease the new scene in over it.
				previousBackdrop = outgoing
				fadeStartSeconds = timeSeconds

				return
			}

			if (current !== outgoing) {
				// A scrolling toggle changes the virtual scene width; crossfading bitmaps with different viewports would slide the outgoing scene under the new one.
				previousBackdrop = null
			}
		}

		/** Draws the scene, crossfading only world-space content before the screen-anchored effects are composited once at full strength. */
		private fun drawScene(canvas: Canvas, backdrop: Bitmap, params: SceneParams, timeSeconds: Float, celebrationEpochMillis: Long, viewportLeft: Float) {
			val outgoing = previousBackdrop
			val elapsed = timeSeconds - fadeStartSeconds
			if (outgoing == null || elapsed < 0f || elapsed >= SCENE_FADE_SECONDS) {
				previousBackdrop = null
				drawWorldViewport(canvas, backdrop, params, timeSeconds, celebrationEpochMillis, viewportLeft)
			} else {
				/*
				 * A smoothstepped layer alpha eases the incoming scene in over the outgoing backdrop.
				 * The extra saveLayerAlpha compositing exists only while a fade runs — steady-state rendering never pays for it — and it happens to mask the incoming scene's first-frame tile rebuild too.
				 * A negative elapsed means the fade straddled the clock wrap; the guard above just ends it.
				 */
				val linear = elapsed / SCENE_FADE_SECONDS
				val eased = linear * linear * (3f - 2f * linear)
				canvas.drawBitmap(outgoing, -viewportLeft, 0f, null)

				val alpha = (eased * 255f).roundToInt().coerceIn(0, 255)
				val saved = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
				drawWorldViewport(canvas, backdrop, params, timeSeconds, celebrationEpochMillis, viewportLeft)
				canvas.restoreToCount(saved)
			}

			screenEffects.render(canvas, width, height, params, timeSeconds)
		}

		/** Draws one world-space viewport into the wider virtual wallpaper scene without scaling it, so launcher offsets reveal real off-screen content instead of stretching the current frame. */
		private fun drawWorldViewport(canvas: Canvas, backdrop: Bitmap, params: SceneParams, timeSeconds: Float, celebrationEpochMillis: Long, viewportLeft: Float) {
			canvas.drawBitmap(backdrop, -viewportLeft, 0f, null)

			val saved = canvas.save()
			canvas.translate(-viewportLeft, 0f)
			renderer.lensFlareOffsetX = lensFlareMotionSensor.offsetX
			renderer.lensFlareOffsetY = lensFlareMotionSensor.offsetY
			renderer.renderForeground(
				canvas,
				backdrop.width,
				height,
				params,
				timeSeconds,
				includeOverlayLabels = false,
				celebrationEpochMillis = celebrationEpochMillis
			)
			canvas.restoreToCount(saved)
		}

		/** Repeated frames do not add leases; only eligibility and lifecycle transitions touch the sensor. */
		private fun updateReflectionMotion(active: Boolean) {
			if (reflectionMotionActive == active) {
				return
			}

			reflectionMotionActive = active
			lensFlareMotionSensor.setActive(this, active)
		}

		/** Settings and weather invalidate immediately; unchanged inputs only need a clock update once per second. */
		private fun currentParams(): SceneParams {
			val second = nowEpochSeconds()
			val state = sceneProvider.sceneState.value
			val cached = activeParams
			if (cached != null && state === activeSceneState && second == paramsComputedAtSecond) {
				return cached
			}

			return sceneProvider.paramsFor(second, state).also {
				activeParams = it
				activeSceneState = state
				paramsComputedAtSecond = second
			}
		}

		/** The cached static backdrop, re-rasterized only when a backdrop-relevant part of the scene changes. */
		private fun backdropFor(params: SceneParams, sceneWidth: Int): Bitmap {
			val current = backdrop
			val backgroundPhotoAvailable = photoBackgroundRepository.hasFor(params.backdropScene, params.dayPhase)
			val signature = backdropSignature(params, backgroundPhotoAvailable)
			if (current != null && current.width == sceneWidth && current.height == height && signature == renderedParams) {
				return current
			}

			val fresh = rasterizeBackdrop(sceneWidth, height, params)
			backdrop = fresh
			renderedParams = signature

			return fresh
		}

		/**
		 * Rasterizes the static backdrop into an immutable bitmap, lending the renderer the user's photo for exactly the length of that one call when the scene asks for one.
		 * A null photo is the ordinary case and not a failure — the user has not chosen a photo backdrop, the phase resolves to no filled bucket, or the stored file no longer decodes — and the renderer then paints its procedural sky exactly as it always has.
		 * The load, the assignment, the rasterize and the release all run here on the render thread, synchronously: [SceneRenderer.backgroundPhoto] is an unsynchronized field the sky pass dereferences mid-blit, so decoding on [Dispatchers.IO] and assigning from there would both race that read and risk recycling the bitmap under it.
		 * The decode costs one file read per backdrop invalidation, and the frame that pays it is already rebuilding the entire backdrop synchronously — sky gradients, overcast ceiling, fog base, haze, vignette — which dwarfs one file read of an already display-sized JPEG.
		 */
		private fun rasterizeBackdrop(width: Int, height: Int, params: SceneParams): Bitmap {
			val photo = photoBackgroundRepository.loadFor(params.backdropScene, params.dayPhase)
			renderer.backgroundPhoto = photo

			try {
				return renderImmutableBitmap(width, height) { canvas ->
					renderer.renderBackdrop(canvas, width, height, params)
				}
			} finally {
				// Clearing before recycling, and in a finally, so a throwing rasterize can neither leak the bitmap nor leave the renderer holding a reference to freed pixels.
				renderer.backgroundPhoto = null
				photo?.recycle()
			}
		}

		private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
	}
}

/** Returns whether continuous animation should still run for the current visible session. */
internal fun wallpaperAnimationActive(timeout: AnimationIdleTimeout, motionStartedAtNanos: Long, nowNanos: Long): Boolean {
	if (timeout == AnimationIdleTimeout.NEVER) {
		return true
	}

	val elapsedNanos = (nowNanos - motionStartedAtNanos).coerceAtLeast(0L)
	return elapsedNanos < timeout.seconds * NANOS_PER_SECOND
}

/** How long a scene flip takes to crossfade — long enough to read as weather moving in, short enough to never lag a glance at the screen. */
private const val SCENE_FADE_SECONDS = 2.8f

/** Settled scenes wake only this often for clock-driven day-phase/celestial updates when no explicit scene change arrives. */
private const val SETTLED_SCENE_REFRESH_MILLIS = 60_000L

private const val NANOS_PER_SECOND = 1_000_000_000L

/** Extra virtual width available to launcher page swipes; 12% total travel keeps the effect visible without turning scenery into a sideways conveyor belt. */
private const val WALLPAPER_PARALLAX_OVERSCAN_FRACTION = 0.12f

internal fun wallpaperSceneWidth(surfaceWidth: Int, scrollingEnabled: Boolean) = if (scrollingEnabled) {
	(surfaceWidth * (1f + WALLPAPER_PARALLAX_OVERSCAN_FRACTION)).roundToInt()
} else {
	surfaceWidth
}

internal fun wallpaperViewportLeft(surfaceWidth: Int, sceneWidth: Int, xOffset: Float, scrollingEnabled: Boolean): Float {
	if (!scrollingEnabled || sceneWidth <= surfaceWidth) {
		return 0f
	}

	return (sceneWidth - surfaceWidth) * normalizeWallpaperOffset(xOffset)
}

internal fun normalizeWallpaperOffset(xOffset: Float) = if (!xOffset.isFinite() || xOffset < 0f) {
	0.5f
} else {
	xOffset.coerceAtMost(1f)
}
