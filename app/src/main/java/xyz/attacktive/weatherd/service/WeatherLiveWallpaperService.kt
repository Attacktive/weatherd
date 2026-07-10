package xyz.attacktive.weatherd.service

import javax.inject.Inject
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
 * Live wallpaper that animates the current weather. The scene comes from [WeatherSceneProvider] (shared with
 * the rest of the app): weather is fetched when the wallpaper becomes visible and cached, while the day phase
 * is re-derived from the clock. The static backdrop is cached and only rebuilt when the scene actually
 * changes — a weather refresh or a dawn/day/dusk/night flip; the animated foreground is redrawn each vsync
 * via Choreographer and gated on visibility, so it costs nothing while the screen is off or covered.
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
			val backdrop = backdropFor(params)
			val holder = surfaceHolder
			var canvas: Canvas? = null

			try {
				// GPU-composited canvas: bitmap blits and primitives are cheap there, where a software
				// canvas at full resolution can't hold 60fps. Falls back if the surface refuses.
				canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: holder.lockCanvas()
				if (canvas != null) {
					canvas.drawBitmap(backdrop, 0f, 0f, null)
					renderer.renderForeground(canvas, width, height, params, timeSeconds)
				}
			} finally {
				if (canvas != null) {
					holder.unlockCanvasAndPost(canvas)
				}
			}
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

		/** The cached static backdrop, re-rasterised only when the scene changes. */
		private fun backdropFor(params: SceneParams): Bitmap {
			val current = backdrop
			if (current != null && params == renderedParams) {
				return current
			}

			val fresh = createBitmap(width, height)
			renderer.renderBackdrop(Canvas(fresh), width, height, params)
			backdrop = fresh
			renderedParams = params
			return fresh
		}

		private fun nowEpochSeconds() = System.currentTimeMillis() / 1000L
	}
}

/** Six hours: long enough that the wrap's one discontinuous frame is rare, short enough that timeSeconds never loses sub-frame float precision. */
private const val CLOCK_WRAP_NANOS = 21_600L * 1_000_000_000L
