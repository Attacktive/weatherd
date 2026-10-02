package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF

/** Cached, disjoint sampling regions omit transparent overdraw without changing the source bitmap, filtering, or blend mode. */
internal class SunSpriteCoverage(private val bitmap: Bitmap) {
	private val regions = buildRegions(bitmap)
	private val clip = RectF()

	fun draw(canvas: Canvas, destination: RectF, paint: Paint) {
		if (paint.alpha == 0) {
			return
		}

		drawRegions(canvas, destination) { canvas.drawBitmap(bitmap, null, destination, paint) }
	}

	fun draw(canvas: Canvas, transform: Matrix, destination: RectF, paint: Paint) {
		if (paint.alpha == 0) {
			return
		}

		drawRegions(canvas, destination) { canvas.drawBitmap(bitmap, transform, paint) }
	}

	private inline fun drawRegions(canvas: Canvas, destination: RectF, draw: () -> Unit) {
		val width = destination.width()
		val height = destination.height()
		for (index in regions.indices) {
			val region = regions[index]
			clip.set(destination.left + region.left * width, destination.top + region.top * height, destination.left + region.right * width, destination.top + region.bottom * height)
			val saved = canvas.save()
			try {
				if (canvas.clipRect(clip)) {
					// Keep the full sampling transform: drawing cropped bitmap source rectangles would clamp filtering at every strip edge.
					draw()
				}
			} finally {
				canvas.restoreToCount(saved)
			}
		}
	}

	private companion object {
		const val STRIP_HEIGHT = 16

		fun buildRegions(bitmap: Bitmap): List<RectF> {
			val width = bitmap.width
			val height = bitmap.height
			val pixels = IntArray(width * minOf(height, STRIP_HEIGHT + 2))
			val columns = BooleanArray(width)
			val regions = mutableListOf<RectF>()
			for (top in 0 until height step STRIP_HEIGHT) {
				val bottom = minOf(top + STRIP_HEIGHT, height)
				val sampleTop = maxOf(0, top - 1)
				val sampleHeight = minOf(height, bottom + 1) - sampleTop
				bitmap.getPixels(pixels, 0, width, 0, sampleTop, width, sampleHeight)
				markCoveredColumns(pixels, width, sampleHeight, columns)

				var x = 0
				while (x < width) {
					if (!columns[x]) {
						x++
						continue
					}

					val left = x
					while (x < width && columns[x]) {
						x++
					}

					regions.add(RectF(left.toFloat() / width, top.toFloat() / height, x.toFloat() / width, bottom.toFloat() / height))
				}
			}

			return regions
		}

		private fun markCoveredColumns(pixels: IntArray, width: Int, sampleHeight: Int, columns: BooleanArray) {
			columns.fill(false)
			for (y in 0 until sampleHeight) {
				for (x in 0 until width) {
					if (Color.alpha(pixels[y * width + x]) > 0) {
						// Include neighboring texels so bilinear filtering keeps faint edges and isolated ray tips.
						columns[maxOf(0, x - 1)] = true
						columns[x] = true
						columns[minOf(width - 1, x + 1)] = true
					}
				}
			}
		}
	}
}
