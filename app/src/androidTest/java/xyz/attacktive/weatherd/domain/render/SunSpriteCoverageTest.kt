package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SunSpriteCoverageTest {
	@Test
	fun filteredSparseLightKeepsItsEdgesAndStripSeams() {
		val source = sparseLight()
		val coverage = SunSpriteCoverage(source)
		for (alpha in intArrayOf(0, 1, 37, 128, 255)) {
			for (screen in booleanArrayOf(false, true)) {
				val brush = Paint(Paint.FILTER_BITMAP_FLAG).apply {
					this.alpha = alpha
					if (screen) {
						xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
					}

					colorFilter = LightingColorFilter(Color.rgb(240, 200, 180), Color.BLACK)
				}

				val expected = Bitmap.createBitmap(217, 301, Bitmap.Config.ARGB_8888)
				val actual = Bitmap.createBitmap(217, 301, Bitmap.Config.ARGB_8888)
				expected.eraseColor(Color.rgb(44, 104, 182))
				actual.eraseColor(Color.rgb(44, 104, 182))
				val destination = RectF(-13.25f, 3.125f, 206.75f, 297.375f)
				Canvas(expected).drawBitmap(source, null, destination, brush)
				coverage.draw(Canvas(actual), destination, brush)
				assertSameLight(expected, actual, "alpha=$alpha screen=$screen")
				expected.recycle()
				actual.recycle()
			}
		}

		source.recycle()
	}

	@Test
	fun transformedLightPreservesTransparentHolesAndSinglePixelTips() {
		val source = sparseLight()
		val coverage = SunSpriteCoverage(source)
		val expected = Bitmap.createBitmap(151, 173, Bitmap.Config.ARGB_8888)
		val actual = Bitmap.createBitmap(151, 173, Bitmap.Config.ARGB_8888)
		val destination = RectF(0.25f, -2.75f, 125.875f, 121.625f)
		val brush = Paint(Paint.FILTER_BITMAP_FLAG)
		val originalCanvas = Canvas(expected)
		val coveredCanvas = Canvas(actual)
		for (canvas in listOf(originalCanvas, coveredCanvas)) {
			canvas.translate(11.75f, 9.125f)
			canvas.rotate(17f, 60f, 60f)
			canvas.clipRect(3f, 5f, 119f, 121f)
		}

		originalCanvas.drawBitmap(source, null, destination, brush)
		coverage.draw(coveredCanvas, destination, brush)
		originalCanvas.drawCircle(12f, 112f, 4f, brush)
		coveredCanvas.drawCircle(12f, 112f, 4f, brush)
		assertSameLight(expected, actual, "rotated transparent light")
		expected.recycle()
		actual.recycle()
		source.recycle()
	}

	@Test
	fun downscaledMatrixSamplingKeepsTheHaloAndLaterDrawing() {
		val source = sparseLight()
		val coverage = SunSpriteCoverage(source)
		val expected = Bitmap.createBitmap(83, 97, Bitmap.Config.ARGB_8888)
		val actual = Bitmap.createBitmap(83, 97, Bitmap.Config.ARGB_8888)
		val transform = Matrix().apply {
			setScale(0.475f, 0.625f)
			postTranslate(13.125f, 11.75f)
		}

		val destination = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
		transform.mapRect(destination)
		val brush = Paint(Paint.FILTER_BITMAP_FLAG)
		val originalCanvas = Canvas(expected)
		val coveredCanvas = Canvas(actual)
		originalCanvas.drawBitmap(source, transform, brush)
		coverage.draw(coveredCanvas, transform, destination, brush)
		originalCanvas.drawColor(Color.argb(64, 255, 255, 255))
		coveredCanvas.drawColor(Color.argb(64, 255, 255, 255))
		assertSameLight(expected, actual, "downscaled halo and later full-canvas drawing")
		expected.recycle()
		actual.recycle()
		source.recycle()
	}

	private fun sparseLight(): Bitmap {
		val bitmap = Bitmap.createBitmap(65, 67, Bitmap.Config.ARGB_8888)
		val canvas = Canvas(bitmap)
		val brush = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(191, 255, 232, 201)
			style = Paint.Style.STROKE
			strokeWidth = 3.5f
		}

		canvas.drawCircle(32.5f, 33.5f, 23.5f, brush)
		canvas.drawLine(7.5f, 2.5f, 54.5f, 64.5f, brush)
		bitmap.setPixel(0, 0, Color.WHITE)
		bitmap.setPixel(64, 66, Color.WHITE)
		return bitmap
	}

	private fun assertSameLight(expected: Bitmap, actual: Bitmap, context: String) {
		val original = IntArray(expected.width * expected.height)
		val covered = IntArray(original.size)
		expected.getPixels(original, 0, expected.width, 0, 0, expected.width, expected.height)
		actual.getPixels(covered, 0, actual.width, 0, 0, actual.width, actual.height)
		for (index in original.indices) {
			val first = original[index]
			val second = covered[index]
			if (abs(Color.alpha(first) - Color.alpha(second)) > 1 || abs(Color.red(first) - Color.red(second)) > 1 || abs(Color.green(first) - Color.green(second)) > 1 || abs(Color.blue(first) - Color.blue(second)) > 1) {
				fail("Light changed at pixel $index ($context): $first -> $second")
			}
		}
	}
}
