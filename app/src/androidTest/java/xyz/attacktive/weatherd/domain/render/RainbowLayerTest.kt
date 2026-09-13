package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.R
import xyz.attacktive.weatherd.domain.model.DayPhase

@RunWith(AndroidJUnit4::class)
class RainbowLayerTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val cloudySky = Color.rgb(160, 174, 184)

	@Test
	fun rainbowApexSitsInTheUpperMiddleOfAPortraitSky() {
		val bitmap = renderTransparent()
		val apex = rowWithHighestAlpha(bitmap)

		assertTrue("The rainbow apex should sit in the upper middle of the sky, but was row $apex", apex in 300..390)
		bitmap.recycle()
	}

	@Test
	fun rainbowArcStaysShallowAcrossAPortraitSky() {
		val bitmap = renderTransparent()
		val center = rowWithHighestAlphaAt(bitmap, bitmap.width / 2)
		val edge = rowWithHighestAlphaAt(bitmap, 0)

		assertTrue("The rainbow should remain shallow, but falls ${abs(edge - center)} pixels from its apex", abs(edge - center) <= 22)
		bitmap.recycle()
	}

	@Test
	fun rainbowRemainsTranslucentAgainstTheSky() {
		val bitmap = renderTransparent()
		val alpha = highestAlpha(bitmap)

		assertTrue("A rainbow should remain atmospheric, but reached alpha $alpha", alpha <= 64)
		bitmap.recycle()
	}

	@Test
	fun rainbowAvoidsNeonSpectralColors() {
		val bitmap = renderOnCloudySky()
		val pixel = mostChromaticPixel(bitmap)
		val chroma = chroma(pixel)

		assertTrue("A rainbow should blend into cloud and haze, but reached chroma $chroma at (${Color.red(pixel)}, ${Color.green(pixel)}, ${Color.blue(pixel)})", chroma <= 50)
		bitmap.recycle()
	}

	@Test
	fun rainbowRemainsDiscernibleAgainstCloudySky() {
		val bitmap = renderOnCloudySky()
		val contrast = highestSkyDisplacement(bitmap)

		assertTrue("A daytime rainbow should remain visible through cloudy haze, but only changed a channel by $contrast", contrast >= 19)
		bitmap.recycle()
	}

	private fun renderTransparent(): Bitmap {
		return render(Color.TRANSPARENT)
	}

	private fun renderOnCloudySky(): Bitmap {
		return render(cloudySky)
	}

	private fun render(background: Int): Bitmap {
		val bitmap = createBitmap(360, 780)
		val canvas = Canvas(bitmap)
		canvas.drawColor(background)
		val layer = RainbowLayer(resources, R.drawable.rainbow)
		layer.draw(canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), DayPhase.DAY)
		return bitmap
	}

	private fun rowWithHighestAlpha(bitmap: Bitmap): Int {
		val pixels = pixels(bitmap)
		var row = 0
		var alpha = -1

		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				val candidate = Color.alpha(pixels[y * bitmap.width + x])
				if (candidate > alpha) {
					alpha = candidate
					row = y
				}
			}
		}

		return row
	}

	private fun rowWithHighestAlphaAt(bitmap: Bitmap, x: Int): Int {
		val pixels = pixels(bitmap)
		var row = 0
		var alpha = -1

		for (y in 0 until bitmap.height) {
			val candidate = Color.alpha(pixels[y * bitmap.width + x])
			if (candidate > alpha) {
				alpha = candidate
				row = y
			}
		}

		return row
	}

	private fun highestAlpha(bitmap: Bitmap): Int {
		return pixels(bitmap).maxOf(Color::alpha)
	}

	private fun mostChromaticPixel(bitmap: Bitmap): Int {
		return pixels(bitmap).maxBy(::chroma)
	}

	private fun chroma(pixel: Int): Int {
		return maxOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) - minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
	}

	private fun highestSkyDisplacement(bitmap: Bitmap): Int {
		return pixels(bitmap).maxOf { pixel ->
			maxOf(
				abs(Color.red(pixel) - Color.red(cloudySky)),
				abs(Color.green(pixel) - Color.green(cloudySky)),
				abs(Color.blue(pixel) - Color.blue(cloudySky)),
			)
		}
	}

	private fun pixels(bitmap: Bitmap): IntArray {
		return IntArray(bitmap.width * bitmap.height).also {
			bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		}
	}
}
