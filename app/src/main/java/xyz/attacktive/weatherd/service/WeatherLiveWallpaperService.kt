package xyz.attacktive.weatherd.service

import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.core.graphics.createBitmap
import dagger.hilt.android.AndroidEntryPoint
import xyz.attacktive.weatherd.domain.model.FrameRateCap
import xyz.attacktive.weatherd.domain.render.SceneParams
import xyz.attacktive.weatherd.domain.render.SceneRenderer
import xyz.attacktive.weatherd.domain.render.WeatherSceneProvider
import xyz.attacktive.weatherd.domain.render.backdropSignature
import xyz.attacktive.weatherd.domain.repository.PhotoBackgroundRepository
import xyz.attacktive.weatherd.domain.repository.SettingsRepository

/**
 * Live wallpaper that animates the current weather.
 * The scene comes from [WeatherSceneProvider] (shared with the rest of the app): weather is fetched when the wallpaper becomes visible and cached, while the day phase is re-derived from the clock.
 * The static backdrop is cached and only rebuilt when the scene actually changes — a weather refresh or a dawn/day/dusk/night flip; the animated foreground is redrawn on every vsync the user's frame-rate cap allows and gated on visibility, so it costs nothing while the screen is off or covered.
 * Scene flips crossfade briefly instead of swapping in one frame.
 */
@AndroidEntryPoint
class WeatherLiveWallpaperService: WallpaperService() {
	@Inject lateinit var sceneProvider: WeatherSceneProvider
	@Inject lateinit var settingsRepository: SettingsRepository
	@Inject lateinit var photoBackgroundRepository: PhotoBackgroundRepository

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
		@Volatile private var frameRateCap = FrameRateCap.UNCAPPED

		init {
			scope.launch {
				settingsRepository.settings.collect { frameRateCap = it.frameRateCap }
			}
		}

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
			scheduleNextFrame()
		}

		/** Asks for the next frame straight away when uncapped, or after the cap's interval — the wait is what lets the CPU idle between draws. */
		private fun scheduleNextFrame() {
			val intervalMillis = frameRateCap.intervalMillis
			if (intervalMillis > 0L) {
				choreographer.postFrameCallbackDelayed(this, intervalMillis)
			} else {
				choreographer.postFrameCallback(this)
			}
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
			rasterizeBackdrop(fresh, params)
			backdrop = fresh
			renderedParams = signature

			return fresh
		}

		/**
		 * Rasterizes the static backdrop into [target], lending the renderer the user's photo for exactly the length of that one call when the scene asks for one.
		 * A null photo is the ordinary case and not a failure — the user has not chosen a photo backdrop, the phase resolves to no filled bucket, or the stored file no longer decodes — and the renderer then paints its procedural sky exactly as it always has.
		 * The load, the assignment, the rasterize and the release all run here on the render thread, synchronously: [SceneRenderer.backgroundPhoto] is an unsynchronized field the sky pass dereferences mid-blit, so decoding on [Dispatchers.IO] and assigning from there would both race that read and risk recycling the bitmap under it.
		 * The decode costs one file read per backdrop invalidation, and the frame that pays it is already rebuilding the entire backdrop synchronously — sky gradients, overcast ceiling, fog base, haze, vignette — which dwarfs one file read of an already display-sized JPEG.
		 */
		private fun rasterizeBackdrop(target: Bitmap, params: SceneParams) {
			val photo = photoBackgroundRepository.loadFor(params.backdropScene, params.dayPhase)
			renderer.backgroundPhoto = photo

			try {
				val canvas = Canvas(target)
				renderer.renderBackdrop(canvas, width, height, params)
				applyDither(canvas, width, height)
			} finally {
				// Clearing before recycling, and in a finally, so a throwing rasterize can neither leak the bitmap nor leave the renderer holding a reference to freed pixels.
				renderer.backgroundPhoto = null
				photo?.recycle()
			}
		}

		/**
		 * Scatters a faint per-pixel noise over smooth gradients so the eye reads continuous tone instead of discrete steps.
		 * The noise is invisible on content-rich areas and imperceptible on gradients — just enough to push neighboring 8-bit values apart below the banding threshold.
		 */
		private fun applyDither(canvas: Canvas, width: Int, height: Int) {
			val tileSize = 256
			val noise = Bitmap.createBitmap(tileSize, tileSize, Bitmap.Config.ARGB_8888)
			val random = Random(0)
			val pixels = IntArray(tileSize * tileSize)
			for (i in pixels.indices) {
				val v = random.nextInt(5)
				pixels[i] = Color.argb(v, 128, 128, 128)
			}

			noise.setPixels(pixels, 0, tileSize, 0, 0, tileSize, tileSize)

			val noisePaint = Paint().apply {
				shader = BitmapShader(noise, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
				alpha = 20
			}

			canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), noisePaint)
			noise.recycle()
		}

		private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
	}
}

/** Six hours: long enough that the wrap's one discontinuous frame is rare, short enough that timeSeconds never loses sub-frame float precision. */
private const val CLOCK_WRAP_NANOS = 21_600L * 1_000_000_000L

/** How long a scene flip takes to crossfade — long enough to read as weather moving in, short enough to never lag a glance at the screen. */
private const val SCENE_FADE_SECONDS = 2.8f
