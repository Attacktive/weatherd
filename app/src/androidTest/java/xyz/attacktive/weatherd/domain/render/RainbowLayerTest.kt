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
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

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
		val pair = renderPair(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams())
		val center = sunCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val profile = radialProfile(pair, center, PI / 2.0, span)
		val peak = profile.maxOrNull() ?: 0
		val quarterPeakWidth = profile.count { it >= peak / 4 }

		assertTrue("The halo must remain visible enough to measure its falloff, but peaked at $peak", peak >= 8)
		assertTrue("The halo band should be broad, but its quarter-peak width was $quarterPeakWidth pixels", quarterPeakWidth >= span * 0.10f)
		assertTrue("The halo band should feather away rather than wash the sky, but its quarter-peak width was $quarterPeakWidth pixels", quarterPeakWidth <= span * 0.30f)
		pair.recycle()
	}

	@Test
	fun haloRemainsTranslucentAgainstTheSky() {
		val bitmap = renderLayer(Color.TRANSPARENT)
		val alpha = highestAlpha(bitmap)

		assertTrue("An optical halo should remain atmospheric, but reached alpha $alpha", alpha <= 64)
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

		assertTrue("A daytime halo should remain visible through cloudy haze, but only changed a channel by $contrast", contrast >= 15)
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

		assertTrue("The inner halo edge should carry subtle warm dispersion, but its warm shift was $innerWarmShift", innerWarmShift >= 2)
		assertTrue("The outer halo edge should carry subtle cool dispersion, but its cool shift was $outerCoolShift", outerCoolShift >= 2)
		bitmap.recycle()
	}

	@Test
	fun haloDrawsNothingAtNight() {
		val bitmap = renderLayer(Color.TRANSPARENT, DayPhase.NIGHT)

		assertTrue("The daytime-only halo must leave every night pixel transparent", pixels(bitmap).all { Color.alpha(it) == 0 })
		bitmap.recycle()
	}

	@Test
	fun cloudsAndPrecipitationCompositeAboveTheHalo() {
		val precipitation = Precipitation(PrecipitationKind.RAIN, severity = 1f, observed = 1f)
		val params = clearParams(cloudiness = 0.85f, precipitation = precipitation)
		val halo = renderLayer(cloudySky)
		val weatherMask = renderForeground(params.copy(showRainbow = false))
		val withoutHalo = renderForeground(params.copy(showRainbow = false), cloudySky)
		val withHalo = renderForeground(params.copy(showRainbow = true), cloudySky)
		val stats = haloOcclusionStats(halo, weatherMask, withoutHalo, withHalo)

		assertTrue("The test scene should expose the halo between weather layers, but found ${stats.exposedCount} pixels", stats.exposedCount >= MIN_EXPOSED_PIXELS)
		assertTrue("The test scene should overlap the halo with clouds or rain, but found ${stats.occludedCount} pixels", stats.occludedCount >= MIN_OCCLUDED_PIXELS)
		assertTrue("Clouds and rain should attenuate the halo from ${stats.exposedAverage} to below ${stats.exposedAverage * MAX_OCCLUDED_CONTRIBUTION}", stats.occludedAverage < stats.exposedAverage * MAX_OCCLUDED_CONTRIBUTION)
		halo.recycle()
		weatherMask.recycle()
		withoutHalo.recycle()
		withHalo.recycle()
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
			assertTrue("The halo should be visible around the sun, but its strongest alpha was ${peak.strength}", peak.strength >= 32)
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

	private fun haloOcclusionStats(halo: Bitmap, weatherMask: Bitmap, withoutHalo: Bitmap, withHalo: Bitmap): OcclusionStats {
		val haloPixels = pixels(halo)
		val weatherPixels = pixels(weatherMask)
		val withoutPixels = pixels(withoutHalo)
		val withPixels = pixels(withHalo)
		var exposedCount = 0
		var exposedTotal = 0L
		var occludedCount = 0
		var occludedTotal = 0L

		for (index in haloPixels.indices) {
			if (colorDistance(haloPixels[index], cloudySky) < MIN_VISIBLE_HALO_DISPLACEMENT) {
				continue
			}

			val contribution = colorDistance(withPixels[index], withoutPixels[index])
			when {
				Color.alpha(weatherPixels[index]) <= EXPOSED_WEATHER_ALPHA -> {
					exposedCount++
					exposedTotal += contribution
				}
				Color.alpha(weatherPixels[index]) >= OCCLUDED_WEATHER_ALPHA -> {
					occludedCount++
					occludedTotal += contribution
				}
			}
		}

		return OcclusionStats(
			exposedCount = exposedCount,
			exposedAverage = exposedTotal.toFloat() / exposedCount.coerceAtLeast(1),
			occludedCount = occludedCount,
			occludedAverage = occludedTotal.toFloat() / occludedCount.coerceAtLeast(1),
		)
	}

	private fun sunCenter(width: Int, height: Int) = PixelPoint(
		x = (width * SUN_X_FRACTION).roundToInt(),
		y = (height * MIDDAY_SUN_Y_FRACTION).roundToInt(),
	)

	private fun renderPair(width: Int, height: Int, params: SceneParams): RenderPair {
		return RenderPair(
			withHalo = renderScene(width, height, params.copy(showRainbow = true)),
			withoutHalo = renderScene(width, height, params.copy(showRainbow = false)),
		)
	}

	private fun renderScene(width: Int, height: Int, params: SceneParams): Bitmap {
		val bitmap = createBitmap(width, height)
		SceneRenderer(resources).render(Canvas(bitmap), width, height, params, TIME_SECONDS)

		return bitmap
	}

	private fun renderForeground(params: SceneParams, background: Int = Color.TRANSPARENT): Bitmap {
		val bitmap = createBitmap(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val canvas = Canvas(bitmap)
		canvas.drawColor(background)
		SceneRenderer(resources).renderForeground(canvas, bitmap.width, bitmap.height, params, TIME_SECONDS)

		return bitmap
	}

	private fun renderLayer(background: Int, dayPhase: DayPhase = DayPhase.DAY, width: Int = PORTRAIT_WIDTH, height: Int = PORTRAIT_HEIGHT): Bitmap {
		val bitmap = createBitmap(width, height)
		val canvas = Canvas(bitmap)
		canvas.drawColor(background)
		RainbowLayer(resources, R.drawable.rainbow).draw(canvas, bitmap.width.toFloat(), bitmap.height.toFloat(), bitmap.width * SUN_X_FRACTION, bitmap.height * MIDDAY_SUN_Y_FRACTION, dayPhase)

		return bitmap
	}

	private fun clearParams(
		cloudiness: Float = 0f,
		precipitation: Precipitation? = null,
	) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = cloudiness,
		fogDensity = 0f,
		precipitation = precipitation,
		thunder = false,
		windFactor = 0.2f,
		showRainbow = true,
		celestialProgress = 0.5f,
	)

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

	private data class OcclusionStats(
		val exposedCount: Int,
		val exposedAverage: Float,
		val occludedCount: Int,
		val occludedAverage: Float,
	)

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
		const val TIME_SECONDS = 0f
		const val SUN_X_FRACTION = 0.72f
		const val MIDDAY_SUN_Y_FRACTION = 0.17f
		const val HALO_RADIUS_FRACTION = 0.40f
		const val SPECTRAL_SAMPLE_OFFSET_FRACTION = 0.035f
		const val MIN_SEARCH_RADIUS_FRACTION = 0.28f
		const val MAX_SEARCH_RADIUS_FRACTION = 0.62f
		const val RADIUS_TOLERANCE_FRACTION = 0.035f
		const val CIRCULARITY_TOLERANCE_FRACTION = 0.045f
		const val MIN_VISIBLE_HALO_DISPLACEMENT = 4
		const val EXPOSED_WEATHER_ALPHA = 32
		const val OCCLUDED_WEATHER_ALPHA = 160
		const val MIN_EXPOSED_PIXELS = 100
		const val MIN_OCCLUDED_PIXELS = 10
		const val MAX_OCCLUDED_CONTRIBUTION = 0.65f
	}
}
