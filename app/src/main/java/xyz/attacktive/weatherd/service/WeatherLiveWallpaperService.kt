package xyz.attacktive.weatherd.service

import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.Canvas
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.core.graphics.createBitmap
import dagger.hilt.android.AndroidEntryPoint
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.SceneRenderer
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider

/**
 * Live wallpaper that animates the current weather.
 * The scene comes from [WeatherSceneProvider] (shared with the rest of the app): weather is fetched when the wallpaper becomes visible and cached, while the day phase is re-derived from the clock.
 * The static backdrop is cached and only rebuilt when the scene actually changes — a weather refresh or a dawn/day/dusk/night flip; the animated foreground is redrawn each vsync via Choreographer and gated on visibility, so it costs nothing while the screen is off or covered.
 * Scene flips crossfade briefly instead of swapping in one frame.
 */
@AndroidEntryPoint
class WeatherLiveWallpaperService: WallpaperService() {
	@Inject lateinit var sceneProvider: WeatherSceneProvider

	override fun onCreateEngine(): Engine = SceneEngine()

	private inner class SceneEngine: Engine(), Choreographer.FrameCallback {
		private val renderer = SceneRenderer()
		private val choreographer = Choreographer.getInstance()
		private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

		private var backdrop: Bitmap? = null
		private var renderedParams: SceneParams? = null
		private var previousBackdrop: Bitmap? = null
		private var fadeStartSeconds = 0f
		private var activeParams: SceneParams? = null
		private var paramsComputedAtSecond = 0L
		private var width = 0
		private var height = 0
		private var visible = false
		private var startNanos = 0L

		override fun onVisibilityChanged(visible: Boolean) {
			this.visible = visible
			choreographer.removeFrameCallback(this)

			if (visible) {
				scope.launch { sceneProvider.refresh(nowEpochSeconds()) }
				choreographer.postFrameCallback(this)
			}
		}

		override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
			this.width = width
			this.height = height
			backdrop = null
			renderedParams = null
			previousBackdrop = null
		}

		override fun onSurfaceDestroyed(holder: SurfaceHolder) {
			choreographer.removeFrameCallback(this)
		}

		override fun onDestroy() {
			choreographer.removeFrameCallback(this)
			scope.cancel()
		}

		override fun doFrame(frameTimeNanos: Long) {
			if (!visible) {
				return
			}

			if (startNanos == 0L) {
				startNanos = frameTimeNanos
			}

			// The clock wraps periodically: past days of cumulative visible time, a Float second count's ulp approaches a frame step and the slow scene oscillators would visibly stutter.
			val elapsedNanos = (frameTimeNanos - startNanos) % CLOCK_WRAP_NANOS

			drawFrame(elapsedNanos / 1_000_000_000f)
			choreographer.postFrameCallback(this)
		}

		private fun drawFrame(timeSeconds: Float) {
			if (width == 0 || height == 0) {
				return
			}

			val params = currentParams()
			val outgoing = backdrop
			val current = backdropFor(params)
			if (outgoing != null && current !== outgoing) {
				// The scene flipped: keep the outgoing backdrop around and ease the new scene in over it.
				previousBackdrop = outgoing
				fadeStartSeconds = timeSeconds
			}

			val holder = surfaceHolder
			var canvas: Canvas? = null

			try {
				/*
				 * GPU-composited canvas: bitmap blits and primitives are cheap there, where a software canvas at full resolution can't hold 60fps.
				 * Falls back if the surface refuses.
				 */
				canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: holder.lockCanvas()
				if (canvas != null) {
					drawScene(canvas, current, params, timeSeconds)
				}
			} finally {
				if (canvas != null) {
					holder.unlockCanvasAndPost(canvas)
				}
			}
		}

		/** Draws the scene, crossfading from the outgoing backdrop for a moment after a scene flip. */
		private fun drawScene(canvas: Canvas, backdrop: Bitmap, params: SceneParams, timeSeconds: Float) {
			val outgoing = previousBackdrop
			val elapsed = timeSeconds - fadeStartSeconds
			if (outgoing == null || elapsed < 0f || elapsed >= SCENE_FADE_SECONDS) {
				previousBackdrop = null
				canvas.drawBitmap(backdrop, 0f, 0f, null)
				renderer.renderForeground(canvas, width, height, params, timeSeconds)

				return
			}

			/*
			 * A smoothstepped layer alpha eases the incoming scene in over the outgoing backdrop.
			 * The extra saveLayerAlpha compositing exists only while a fade runs — steady-state rendering never pays for it — and it happens to mask the incoming scene's first-frame tile rebuild too.
			 * A negative elapsed means the fade straddled the clock wrap; the guard above just ends it.
			 */
			val linear = elapsed / SCENE_FADE_SECONDS
			val eased = linear * linear * (3f - 2f * linear)
			canvas.drawBitmap(outgoing, 0f, 0f, null)

			val alpha = (eased * 255f).roundToInt().coerceIn(0, 255)
			val saved = canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), alpha)
			canvas.drawBitmap(backdrop, 0f, 0f, null)
			renderer.renderForeground(canvas, width, height, params, timeSeconds)
			canvas.restoreToCount(saved)
		}

		/** The scene params, recomputed at most once per second — the day phase can shift, but never per frame. */
		private fun currentParams(): SceneParams {
			val second = nowEpochSeconds()
			val cached = activeParams
			if (cached != null && second == paramsComputedAtSecond) {
				return cached
			}

			return sceneProvider.paramsFor(second).also {
				activeParams = it
				paramsComputedAtSecond = second
			}
		}

		/** The cached static backdrop, re-rasterized only when a backdrop-relevant part of the scene changes. */
		private fun backdropFor(params: SceneParams): Bitmap {
			val current = backdrop
			val signature = backdropSignature(params)
			if (current != null && signature == renderedParams) {
				return current
			}

			val fresh = createBitmap(width, height)
			renderer.renderBackdrop(Canvas(fresh), width, height, params)
			backdrop = fresh
			renderedParams = signature

			return fresh
		}

		/** The backdrop never draws the moon or the sun's arc, so their slow foreground-only ticks must not force a re-rasterize. */
		private fun backdropSignature(params: SceneParams) = params.copy(moonPhase = 0f, celestialProgress = 0f)

		private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
	}
}

/** Six hours: long enough that the wrap's one discontinuous frame is rare, short enough that timeSeconds never loses sub-frame float precision. */
private const val CLOCK_WRAP_NANOS = 21_600L * 1_000_000_000L

/** How long a scene flip takes to crossfade — long enough to read as weather moving in, short enough to never lag a glance at the screen. */
private const val SCENE_FADE_SECONDS = 2.8f
