package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas

/** Cloud drift is slow relative to the moving water mesh; sample the sky independently of the display frame rate. */
internal class LakeSkyReflection {
	private val pixels = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
	private val canvas = Canvas(pixels)
	private var lastParams: SceneParams? = null
	private var lastWidth = 0
	private var lastHeight = 0
	private var lastSample = Int.MIN_VALUE
	private var lastCloudsReady = false

	inline fun sample(width: Int, height: Int, params: SceneParams, timeSeconds: Float, cloudsReady: Boolean, draw: (Canvas) -> Unit): Bitmap {
		val sample = (timeSeconds * 2f).toInt()
		if (lastSample != sample || lastWidth != width || lastHeight != height || lastCloudsReady != cloudsReady || lastParams != params) {
			val save = canvas.save()
			canvas.scale(pixels.width.toFloat() / width, pixels.height.toFloat() / height)
			draw(canvas)
			canvas.restoreToCount(save)
			lastWidth = width
			lastHeight = height
			lastParams = params
			lastSample = sample
			lastCloudsReady = cloudsReady
		}

		return pixels
	}
}
