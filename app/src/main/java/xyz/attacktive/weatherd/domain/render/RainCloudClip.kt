package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import androidx.core.graphics.withSave

internal class RainCloudClip {
	val boundary = CloudRainBoundary()
	private val path = Path()

	inline fun drawBelowClouds(canvas: Canvas, draw: () -> Unit) {
		canvas.withSave {
			clip(this)
			draw()
		}
	}

	/** Call inside a canvas save/restore block limited to the precipitation pass. */
	fun clip(canvas: Canvas) {
		if (!boundary.hasVisibleAreaBelowClouds()) {
			return
		}

		val edges = boundary.lowerEdges
		path.rewind()
		path.moveTo(0f, edges[0])

		for (index in 1 until edges.size) {
			path.lineTo(boundary.width * index / (edges.size - 1), edges[index])
		}

		path.lineTo(boundary.width, boundary.height)
		path.lineTo(0f, boundary.height)
		path.close()
		canvas.clipPath(path)
	}
}

/** Called only while prewarming cloud textures, never from draw. */
internal fun Bitmap.rainCloudProfile(): CloudAlphaProfile {
	val pixels = IntArray(width * height)
	getPixels(pixels, 0, width, 0, 0, width, height)

	return CloudAlphaProfile.fromPixels(width, height, pixels)
}
