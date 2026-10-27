package xyz.attacktive.weatherd.domain.render

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Paint
import android.graphics.RectF

/** Prepares an RGB-only core patch; the original filtered artwork still draws everywhere outside it. */
internal class SunCoreTint(private val source: Bitmap, private val centerX: Float, private val centerY: Float, private val radius: Float, tint: Int) {
	private val sourceLeft = (floor(centerX - radius).toInt() - 3).coerceAtLeast(0)
	private val sourceTop = (floor(centerY - radius).toInt() - 3).coerceAtLeast(0)
	private val patchWidth = (ceil(centerX + radius).toInt() + 3).coerceAtMost(source.width) - sourceLeft
	private val patchHeight = (ceil(centerY + radius).toInt() + 3).coerceAtMost(source.height) - sourceTop
	private val destinationCore = RectF()
	private val destinationPatch = RectF()
	private val originalPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply { colorFilter = LightingColorFilter(tint, 0) }
	private val corePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
	private val corrected = prepareCore(tint)

	fun draw(canvas: Canvas, destination: RectF, alpha: Int) {
		val scaleX = destination.width() / source.width
		val scaleY = destination.height() / source.height
		destinationCore.set(
			floor(destination.left + (centerX - radius - 1f) * scaleX),
			floor(destination.top + (centerY - radius - 1f) * scaleY),
			ceil(destination.left + (centerX + radius + 1f) * scaleX),
			ceil(destination.top + (centerY + radius + 1f) * scaleY)
		)

		destinationPatch.set(destination.left + sourceLeft * scaleX, destination.top + sourceTop * scaleY, destination.left + (sourceLeft + patchWidth) * scaleX, destination.top + (sourceTop + patchHeight) * scaleY)
		originalPaint.alpha = alpha
		// At small scales, outward-rounded core bounds can exceed the patch's source-pixel padding.
		if (!destinationCore.intersect(ceil(destinationPatch.left), ceil(destinationPatch.top), floor(destinationPatch.right), floor(destinationPatch.bottom))) {
			canvas.drawBitmap(source, null, destination, originalPaint)
			return
		}

		corePaint.alpha = alpha
		val outside = canvas.save()
		canvas.clipOutRect(destinationCore)
		canvas.drawBitmap(source, null, destination, originalPaint)
		canvas.restoreToCount(outside)
		val inside = canvas.save()
		canvas.clipRect(destinationCore)
		canvas.drawBitmap(corrected, null, destinationPatch, corePaint)
		canvas.restoreToCount(inside)
	}

	private fun prepareCore(tint: Int): Bitmap {
		val pixels = IntArray(patchWidth * patchHeight)
		source.getPixels(pixels, 0, patchWidth, sourceLeft, sourceTop, patchWidth, patchHeight)
		for (y in 0 until patchHeight) {
			for (x in 0 until patchWidth) {
				val index = y * patchWidth + x
				val color = pixels[index]
				val dx = (sourceLeft + x + 0.5f - centerX) / radius
				val dy = (sourceTop + y + 0.5f - centerY) / radius
				val remaining = ((1f - dx * dx - dy * dy) / 0.8f).coerceIn(0f, 1f)
				val lift = 0.9f * remaining * remaining * (3f - 2f * remaining)
				val red = Color.red(color) * Color.red(tint) / 255f
				val green = Color.green(color) * Color.green(tint) / 255f
				val blue = Color.blue(color) * Color.blue(tint) / 255f
				pixels[index] = Color.argb(Color.alpha(color), (red + (255f - red) * lift).roundToInt(), (green + (255f - green) * lift).roundToInt(), (blue + (255f - blue) * lift).roundToInt())
			}
		}

		return Bitmap.createBitmap(pixels, patchWidth, patchHeight, Bitmap.Config.ARGB_8888)
	}
}
