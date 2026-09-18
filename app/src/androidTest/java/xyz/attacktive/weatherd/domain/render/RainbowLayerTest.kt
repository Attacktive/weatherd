package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
	fun haloFormsACircleAroundTheSunInPortrait() {
		assertCircularHalo(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
	}

	@Test
	fun haloKeepsItsSunRelativeRadiusInLandscape() {
		assertCircularHalo(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
	}

	@Test
	fun haloHasABroadFeatheredBand() {
		val pair = renderPair(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val center = sunCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val profile = radialProfile(pair, center, PI / 2.0, span)
		val peak = profile.maxOrNull() ?: 0
		val quarterPeakWidth = profile.count { it >= peak / 4 }

		assertTrue("The halo must remain visible enough to measure its falloff, but peaked at $peak", peak >= 3)
		assertTrue("The halo band should retain a visible feather, but its quarter-peak width was $quarterPeakWidth pixels", quarterPeakWidth >= span * 0.025f)
		assertTrue("The halo band should stay compact around the sun, but its quarter-peak width was $quarterPeakWidth pixels", quarterPeakWidth <= span * 0.11f)
		pair.recycle()
	}

	@Test
	fun haloRemainsTranslucentAgainstTheSky() {
		val bitmap = renderLayer(Color.TRANSPARENT)
		val alpha = highestAlpha(bitmap)

		assertTrue("An optical halo should remain atmospheric, but reached alpha $alpha", alpha <= 32)
		bitmap.recycle()
	}

	@Test
	fun haloAvoidsNeonSpectralColors() {
		val bitmap = renderLayer(cloudySky)
		val pixel = mostChromaticPixel(bitmap)
		val chroma = chroma(pixel)

		assertTrue("The halo should blend into cloud and haze, but reached chroma $chroma at (${Color.red(pixel)}, ${Color.green(pixel)}, ${Color.blue(pixel)})", chroma <= 50)
		bitmap.recycle()
	}

	@Test
	fun haloRemainsDiscernibleAgainstCloudySky() {
		val bitmap = renderLayer(cloudySky)
		val contrast = highestSkyDisplacement(bitmap)

		assertTrue("A daytime halo should remain visible through cloudy haze, but only changed a channel by $contrast", contrast >= 5)
		bitmap.recycle()
	}

	@Test
	fun haloDispersesWarmLightInsideAndCoolLightOutside() {
		val bitmap = renderLayer(cloudySky)
		val center = sunCenter(bitmap.width, bitmap.height)
		val radius = minOf(bitmap.width, bitmap.height) * HALO_RADIUS_FRACTION
		val offset = minOf(bitmap.width, bitmap.height) * SPECTRAL_SAMPLE_OFFSET_FRACTION
		val inner = bitmap.getPixel(center.x, (center.y + radius - offset).roundToInt())
		val outer = bitmap.getPixel(center.x, (center.y + radius + offset).roundToInt())
		val innerWarmShift = Color.red(inner) - Color.red(cloudySky) - (Color.blue(inner) - Color.blue(cloudySky))
		val outerCoolShift = Color.blue(outer) - Color.blue(cloudySky) - (Color.red(outer) - Color.red(cloudySky))

		assertTrue("The inner halo edge should carry subtle warm dispersion, but its warm shift was $innerWarmShift", innerWarmShift >= 1)
		assertTrue("The outer halo edge should carry subtle cool dispersion, but its cool shift was $outerCoolShift", outerCoolShift >= 1)
		bitmap.recycle()
	}

	@Test
	fun haloDrawsNothingAtNight() {
		val bitmap = renderLayer(Color.TRANSPARENT, DayPhase.NIGHT)

		assertTrue("The daytime-only halo must leave every night pixel transparent", pixels(bitmap).all { Color.alpha(it) == 0 })
		bitmap.recycle()
	}

	private fun assertCircularHalo(width: Int, height: Int) {
		val bitmap = renderLayer(Color.TRANSPARENT, width = width, height = height)
		val center = sunCenter(width, height)
		val span = minOf(width, height)
		val expectedRadius = span * HALO_RADIUS_FRACTION
		val peaks = doubleArrayOf(PI / 2.0, PI * 0.75, PI).map { direction ->
			radialPeak(bitmap, center, direction, span)
		}

		for (peak in peaks) {
			assertTrue("The halo should be visible around the sun, but its strongest alpha was ${peak.strength}", peak.strength >= 8)
			assertTrue("The halo radius should follow the shorter side near $expectedRadius pixels, but was ${peak.radius}", abs(peak.radius - expectedRadius) <= span * RADIUS_TOLERANCE_FRACTION)
		}

		val spread = peaks.maxOf { it.radius } - peaks.minOf { it.radius }

		assertTrue("A circular halo should keep a stable radius around the sun, but varied by $spread pixels", spread <= span * CIRCULARITY_TOLERANCE_FRACTION)
		bitmap.recycle()
	}

	private fun radialPeak(bitmap: Bitmap, center: PixelPoint, direction: Double, span: Int): RadialPeak {
		val minimum = (span * MIN_SEARCH_RADIUS_FRACTION).roundToInt()
		val maximum = (span * MAX_SEARCH_RADIUS_FRACTION).roundToInt()
		var peakRadius = minimum
		var peakAlpha = -1

		for (radius in minimum..maximum) {
			val alpha = sampleAlpha(bitmap, center, direction, radius)
			if (alpha > peakAlpha) {
				peakRadius = radius
				peakAlpha = alpha
			}
		}

		return RadialPeak(peakRadius, peakAlpha)
	}

	private fun sampleAlpha(bitmap: Bitmap, center: PixelPoint, direction: Double, radius: Int): Int {
		val sampleX = center.x + (cos(direction) * radius).roundToInt()
		val sampleY = center.y + (sin(direction) * radius).roundToInt()
		var alpha = 0
		var samples = 0

		for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
			for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
				val x = sampleX + dx
				val y = sampleY + dy
				if (x !in 0 until bitmap.width || y !in 0 until bitmap.height) {
					continue
				}

				alpha += Color.alpha(bitmap.getPixel(x, y))
				samples++
			}
		}

		return if (samples > 0) {
			alpha / samples
		} else {
			0
		}
	}

	private fun radialProfile(pair: RenderPair, center: PixelPoint, direction: Double, span: Int): IntArray {
		val maximum = (span * MAX_SEARCH_RADIUS_FRACTION).roundToInt()

		return IntArray(maximum + 1) { radius ->
			sampleDifference(pair, center, direction, radius)
		}
	}

	private fun sampleDifference(pair: RenderPair, center: PixelPoint, direction: Double, radius: Int): Int {
		val sampleX = center.x + (cos(direction) * radius).roundToInt()
		val sampleY = center.y + (sin(direction) * radius).roundToInt()
		var displacement = 0
		var samples = 0

		for (dy in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
			for (dx in -SAMPLE_RADIUS..SAMPLE_RADIUS) {
				val x = sampleX + dx
				val y = sampleY + dy
				if (x !in 0 until pair.withHalo.width || y !in 0 until pair.withHalo.height) {
					continue
				}

				displacement += colorDistance(pair.withHalo.getPixel(x, y), pair.withoutHalo.getPixel(x, y))
				samples++
			}
		}

		return if (samples > 0) {
			displacement / samples
		} else {
			0
		}
	}

	private fun colorDistance(first: Int, second: Int): Int {
		return maxOf(
			abs(Color.red(first) - Color.red(second)),
			abs(Color.green(first) - Color.green(second)),
			abs(Color.blue(first) - Color.blue(second)),
		)
	}

	private fun sunCenter(width: Int, height: Int) = PixelPoint(
		x = (width * SUN_X_FRACTION).roundToInt(),
		y = (height * MIDDAY_SUN_Y_FRACTION).roundToInt(),
	)

	private fun renderPair(width: Int, height: Int): RenderPair {
		return RenderPair(
			withHalo = renderLayer(Color.TRANSPARENT, width = width, height = height),
			withoutHalo = createBitmap(width, height),
		)
	}

	private fun renderLayer(background: Int, dayPhase: DayPhase = DayPhase.DAY, width: Int = PORTRAIT_WIDTH, height: Int = PORTRAIT_HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		canvas.drawColor(background)
		RainbowLayer(resources, R.drawable.rainbow).draw(canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), bitmap.width * SUN_X_FRACTION, bitmap.height * MIDDAY_SUN_Y_FRACTION, dayPhase)

		return bitmap
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

	private data class PixelPoint(val x: Int, val y: Int)

	private data class RadialPeak(val radius: Int, val strength: Int)

	private data class RenderPair(val withHalo: Bitmap, val withoutHalo: Bitmap) {
		fun recycle() {
			withHalo.recycle()
			withoutHalo.recycle()
		}
	}

	private companion object {
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LANDSCAPE_WIDTH = 780
		const val LANDSCAPE_HEIGHT = 360
		const val SAMPLE_RADIUS = 2
		const val SUN_X_FRACTION = 0.72f
		const val MIDDAY_SUN_Y_FRACTION = 0.17f
		const val HALO_RADIUS_FRACTION = 0.145f
		const val SPECTRAL_SAMPLE_OFFSET_FRACTION = 0.014f
		const val MIN_SEARCH_RADIUS_FRACTION = 0.08f
		const val MAX_SEARCH_RADIUS_FRACTION = 0.24f
		const val RADIUS_TOLERANCE_FRACTION = 0.02f
		const val CIRCULARITY_TOLERANCE_FRACTION = 0.025f
	}
}
