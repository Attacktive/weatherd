package xyz.attacktive.weatherd.domain.render

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class MoonRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun waxingAndWaningTerminateOnOppositeSides() {
		val waxing = renderMoon(0.25f)
		val waning = renderMoon(0.75f)
		val center = PixelPoint(256, 256)
		val sampleOffset = 128

		val waxingLitSideAlpha = Color.alpha(waxing.getPixel(center.x + sampleOffset, center.y))
		val waxingDarkSideAlpha = Color.alpha(waxing.getPixel(center.x - sampleOffset, center.y))

		val waningLitSideAlpha = Color.alpha(waning.getPixel(center.x - sampleOffset, center.y))
		val waningDarkSideAlpha = Color.alpha(waning.getPixel(center.x + sampleOffset, center.y))

		assertTrue("Waxing quarter should be illuminated on the right side, but right alpha was $waxingLitSideAlpha", waxingLitSideAlpha >= OPAQUE_ALPHA_THRESHOLD)
		assertEquals("Waxing quarter must leave its unlit hemisphere transparent", 0, waxingDarkSideAlpha)

		assertTrue("Waning quarter should be illuminated on the left side, but left alpha was $waningLitSideAlpha", waningLitSideAlpha >= OPAQUE_ALPHA_THRESHOLD)
		assertEquals("Waning quarter must leave its unlit hemisphere transparent", 0, waningDarkSideAlpha)

		waxing.recycle()
		waning.recycle()
	}

	@Test
	fun lunarFeaturesAreNeverMirroredWhenWaning() {
		val fullMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.50f))
		val waxingGibbous = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.38f))
		val waningGibbous = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.62f))

		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val radius = span * MOON_RADIUS_FRACTION

		// Mare Crisium is located on the Eastern (right) limb (~0.62 radius East, ~0.23 radius North of center).
		val crisiumX = (center.x + radius * 0.62f).roundToInt()
		val crisiumY = (center.y - radius * 0.23f).roundToInt()

		// Symmetric point on the Western (left) limb in Oceanus Procellarum / highlands.
		val oppositeX = (center.x - radius * 0.62f).roundToInt()
		val oppositeY = (center.y - radius * 0.23f).roundToInt()

		val fullCrisium = fullMoon.getPixel(crisiumX, crisiumY)
		val fullOpposite = fullMoon.getPixel(oppositeX, oppositeY)

		val waxingCrisium = waxingGibbous.getPixel(crisiumX, crisiumY)
		val waningCrisium = waningGibbous.getPixel(crisiumX, crisiumY)

		val crisiumLuminance = Color.red(fullCrisium) * 0.299f + Color.green(fullCrisium) * 0.587f + Color.blue(fullCrisium) * 0.114f
		val oppositeLuminance = Color.red(fullOpposite) * 0.299f + Color.green(fullOpposite) * 0.587f + Color.blue(fullOpposite) * 0.114f
		assertTrue("Mare Crisium should be darker than the opposite lunar terrain, but crisium=$crisiumLuminance, opposite=$oppositeLuminance", oppositeLuminance - crisiumLuminance > 15f)
		assertTrue("Waxing moon must preserve the same lunar markings", colorDistance(waxingCrisium, fullCrisium) < colorDistance(waxingCrisium, fullOpposite))

		val crisiumDistanceToOriginal = colorDistance(waningCrisium, fullCrisium)
		val crisiumDistanceToOpposite = colorDistance(waningCrisium, fullOpposite)
		assertTrue("Waning moon must not mirror surface markings; Mare Crisium should remain on the right (dist=$crisiumDistanceToOriginal vs opposite=$crisiumDistanceToOpposite)", crisiumDistanceToOriginal < crisiumDistanceToOpposite)

		fullMoon.recycle()
		waxingGibbous.recycle()
		waningGibbous.recycle()
	}

	@Test
	fun realisticSurfaceDetailIsPresentRatherThanProceduralCircles() {
		val fullMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.50f))
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val radius = span * MOON_RADIUS_FRACTION

		val sampleValues = mutableListOf<Float>()
		val uniqueColors = HashSet<Int>()

		val sampleCount = 14
		for (row in -sampleCount..sampleCount) {
			for (col in -sampleCount..sampleCount) {
				val dx = col.toFloat() / sampleCount
				val dy = row.toFloat() / sampleCount
				if (dx * dx + dy * dy > 0.70f) {
					continue
				}

				val sampleX = (center.x + dx * radius).roundToInt()
				val sampleY = (center.y + dy * radius).roundToInt()
				val pixel = fullMoon.getPixel(sampleX, sampleY)

				uniqueColors.add(pixel)
				val luminance = Color.red(pixel) * 0.299f + Color.green(pixel) * 0.587f + Color.blue(pixel) * 0.114f
				sampleValues.add(luminance)
			}
		}

		val mean = sampleValues.average().toFloat()
		val variance = sampleValues.map { (it - mean) * (it - mean) }.average().toFloat()
		val standardDeviation = sqrt(variance)

		assertTrue("Photographic lunar terrain should exhibit continuous luminance variation (stdDev was $standardDeviation)", standardDeviation > 12f)
		assertTrue("Photographic lunar terrain should contain rich diverse color nuances rather than flat procedural circles (unique colors was ${uniqueColors.size})", uniqueColors.size > 50)

		fullMoon.recycle()
	}

	@Test
	fun exactNewMoonDrawsNoLunarDisc() {
		val newMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0f))
		val bounds = opaqueBounds(newMoon, threshold = OPAQUE_ALPHA_THRESHOLD)

		assertTrue("New moon disc should draw zero opaque pixels, but bounds were $bounds", !bounds.isValid)

		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val radius = span * MOON_RADIUS_FRACTION
		val peakAlpha = highestAlpha(newMoon, center, radius)

		assertTrue("New moon disc area should remain unlit without an opaque black disc or border, but peak alpha was $peakAlpha", peakAlpha < HALO_MAX_ALPHA)

		val withoutMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0f, moonVisible = false))
		assertArrayEquals("New moon must not leave a luminous halo in an otherwise unlit sky", pixels(withoutMoon), pixels(newMoon))
		withoutMoon.recycle()

		newMoon.recycle()
	}

	@Test
	fun exactFullMoonDrawsCompleteLitDisc() {
		val fullMoon = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.5f))
		val expectedCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val bounds = opaqueBounds(fullMoon, threshold = OPAQUE_ALPHA_THRESHOLD)
		val radiusFraction = bounds.height / 2f / minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

		assertTrue("The full moon should remain centered at $expectedCenter, but opaque bounds were $bounds", abs(bounds.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("The full moon should preserve its existing radius near $MOON_RADIUS_FRACTION, but measured $radiusFraction", radiusFraction in MOON_RADIUS_RANGE)

		fullMoon.recycle()
	}

	@Test
	fun moonSizeScalePreservesCenterInPortraitAndLandscape() {
		assertMoonSizeAndCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		assertMoonSizeAndCenter(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
	}

	@Test
	fun quantizedPhaseYieldsStableOutputRegardlessOfRenderHistory() {
		val renderer1 = SceneRenderer(resources)
		val renderer2 = SceneRenderer(resources)

		val render1A = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.246f), renderer = renderer1)
		val render1B = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.254f), renderer = renderer1)

		val render2B = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.254f), renderer = renderer2)
		val render2A = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(moonPhase = 0.246f), renderer = renderer2)

		assertArrayEquals("Moon output for same quantized phase step must be identical regardless of whether 0.246 or 0.254 was rendered first", pixels(render1A), pixels(render2A))
		assertArrayEquals("Moon output for same quantized phase step must be identical on cache hit", pixels(render1A), pixels(render1B))
		assertArrayEquals("Moon output for same quantized phase step must match across separate instances", pixels(render1A), pixels(render2B))

		render1A.recycle()
		render1B.recycle()
		render2A.recycle()
		render2B.recycle()
	}

	@Test
	fun moonIsHiddenDuringDaylightOrWhenPrecipitatingOrDisabled() {
		val daylight = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.DAY, moonPhase = 0.5f))
		val rainy = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.NIGHT, moonPhase = 0.5f, precipitation = Precipitation(PrecipitationKind.RAIN, 0.5f, 0.5f)))
		val hidden = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.NIGHT, moonPhase = 0.5f, moonVisible = false))

		val rainyBounds = opaqueBounds(rainy, threshold = OPAQUE_ALPHA_THRESHOLD)
		val hiddenBounds = opaqueBounds(hidden, threshold = OPAQUE_ALPHA_THRESHOLD)

		assertTrue("Rainy night must not draw the opaque moon disc", !rainyBounds.isValid)
		assertTrue("When moonVisible is false, opaque moon disc must not be drawn", !hiddenBounds.isValid)

		val resizedInDaylight = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearNightParams(dayPhase = DayPhase.DAY, moonPhase = 0.5f, moonSizeScale = 2f))
		assertArrayEquals("Moon size must not alter the daytime Sun", pixels(daylight), pixels(resizedInDaylight))
		resizedInDaylight.recycle()

		daylight.recycle()
		rainy.recycle()
		hidden.recycle()
	}

	@Test
	fun moonSizeScaleIsExcludedFromBackdropIndependence() {
		val params50 = clearNightParams(moonSizeScale = 0.5f)
		val params100 = clearNightParams(moonSizeScale = 1.0f)
		val params200 = clearNightParams(moonSizeScale = 2.0f)

		assertEquals(
			"Moon size scale must be excluded from backdrop signature",
			backdropSignature(params50),
			backdropSignature(params100)
		)

		assertEquals(
			"Moon size scale must be excluded from backdrop signature",
			backdropSignature(params100),
			backdropSignature(params200)
		)

		val backdrop50 = renderBackdrop(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params50)
		val backdrop100 = renderBackdrop(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params100)
		val backdrop200 = renderBackdrop(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params200)

		assertArrayEquals(
			"Backdrop raster must be 100% identical regardless of moon size scale",
			pixels(backdrop50),
			pixels(backdrop100)
		)

		assertArrayEquals(
			"Backdrop raster must be 100% identical regardless of moon size scale",
			pixels(backdrop100),
			pixels(backdrop200)
		)

		backdrop50.recycle()
		backdrop100.recycle()
		backdrop200.recycle()
	}

	private fun assertMoonSizeAndCenter(width: Int, height: Int) {
		val expectedCenter = celestialCenter(width, height)

		val bitmap50 = renderForeground(width, height, clearNightParams(moonPhase = 0.5f, moonSizeScale = 0.5f))
		val bitmap100 = renderForeground(width, height, clearNightParams(moonPhase = 0.5f, moonSizeScale = 1.0f))
		val bitmap200 = renderForeground(width, height, clearNightParams(moonPhase = 0.5f, moonSizeScale = 2.0f))

		val bounds50 = opaqueBounds(bitmap50, threshold = OPAQUE_ALPHA_THRESHOLD)
		val bounds100 = opaqueBounds(bitmap100, threshold = OPAQUE_ALPHA_THRESHOLD)
		val bounds200 = opaqueBounds(bitmap200, threshold = OPAQUE_ALPHA_THRESHOLD)

		assertTrue("Moon 50% must remain centered at $expectedCenter (was ${bounds50.centerX}, ${bounds50.centerY})", abs(bounds50.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds50.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("Moon 100% must remain centered at $expectedCenter (was ${bounds100.centerX}, ${bounds100.centerY})", abs(bounds100.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds100.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("Moon 200% must remain centered at $expectedCenter (was ${bounds200.centerX}, ${bounds200.centerY})", abs(bounds200.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds200.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)

		val ratio200to100 = bounds200.height.toFloat() / bounds100.height.toFloat()
		val ratio50to100 = bounds50.height.toFloat() / bounds100.height.toFloat()

		assertTrue("Moon diameter at 200% should scale by ~2x relative to 100% (was $ratio200to100)", ratio200to100 in 1.85f..2.15f)
		assertTrue("Moon diameter at 50% should scale by ~0.5x relative to 100% (was $ratio50to100)", ratio50to100 in 0.45f..0.55f)

		bitmap50.recycle()
		bitmap100.recycle()
		bitmap200.recycle()
	}

	private fun clearNightParams(
		moonPhase: Float = 0.5f,
		moonSizeScale: Float = 1f,
		moonVisible: Boolean = true,
		dayPhase: DayPhase = DayPhase.NIGHT,
		precipitation: Precipitation? = null,
		cloudiness: Float = 0f,
		fogDensity: Float = 0f
	) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = cloudiness,
		fogDensity = fogDensity,
		precipitation = precipitation,
		thunder = false,
		windFactor = 0.2f,
		moonPhase = moonPhase,
		moonSizeScale = moonSizeScale,
		moonVisible = moonVisible
	)

	private fun renderMoon(phase: Float): Bitmap {
		val bitmap = createBitmap(512, 512)
		val layer = MoonLayer(resources)
		layer.prewarm()
		layer.draw(Canvas(bitmap), 256f, 256f, 256f, phase)

		return bitmap
	}

	private fun renderForeground(
		width: Int,
		height: Int,
		params: SceneParams,
		timeSeconds: Float = 0f,
		renderer: SceneRenderer? = null
	): Bitmap {
		val bitmap = createBitmap(width, height)
		val actualRenderer = renderer ?: SceneRenderer(resources)
		actualRenderer.prewarmMoonTexture()
		actualRenderer.renderForeground(Canvas(bitmap), width, height, params, timeSeconds)

		return bitmap
	}

	private fun renderBackdrop(
		width: Int,
		height: Int,
		params: SceneParams,
		renderer: SceneRenderer? = null
	): Bitmap {
		val bitmap = createBitmap(width, height)
		val actualRenderer = renderer ?: SceneRenderer(resources)
		actualRenderer.renderBackdrop(Canvas(bitmap), width, height, params)

		return bitmap
	}

	private fun celestialCenter(width: Int, height: Int, dayPhase: DayPhase = DayPhase.NIGHT, progress: Float = 0.5f) = PixelPoint(
		x = (width * CELESTIAL_X_FRACTION).roundToInt(),
		y = (height * celestialHeightFraction(dayPhase, progress)).roundToInt()
	)

	private fun opaqueBounds(bitmap: Bitmap, threshold: Int = OPAQUE_ALPHA_THRESHOLD): PixelBounds {
		var left = bitmap.width
		var top = bitmap.height
		var right = -1
		var bottom = -1

		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				if (Color.alpha(bitmap.getPixel(x, y)) < threshold) {
					continue
				}

				left = minOf(left, x)
				top = minOf(top, y)
				right = maxOf(right, x)
				bottom = maxOf(bottom, y)
			}
		}

		return PixelBounds(left, top, right, bottom)
	}

	private fun highestAlpha(bitmap: Bitmap, center: PixelPoint, radius: Float): Int {
		val radiusSquared = radius * radius
		var peak = 0

		val startY = (center.y - radius.toInt()).coerceAtLeast(0)
		val endY = (center.y + radius.toInt()).coerceAtMost(bitmap.height - 1)
		val startX = (center.x - radius.toInt()).coerceAtLeast(0)
		val endX = (center.x + radius.toInt()).coerceAtMost(bitmap.width - 1)

		for (y in startY..endY) {
			for (x in startX..endX) {
				val dx = x - center.x
				val dy = y - center.y
				if (dx * dx + dy * dy > radiusSquared) {
					continue
				}

				peak = maxOf(peak, Color.alpha(bitmap.getPixel(x, y)))
			}
		}

		return peak
	}

	private fun colorDistance(first: Int, second: Int) = maxOf(
		abs(Color.red(first) - Color.red(second)),
		abs(Color.green(first) - Color.green(second)),
		abs(Color.blue(first) - Color.blue(second))
	)

	private fun pixels(bitmap: Bitmap): IntArray {
		val array = IntArray(bitmap.width * bitmap.height)
		bitmap.getPixels(array, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

		return array
	}

	private data class PixelPoint(val x: Int, val y: Int)

	private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		val isValid = left <= right && top <= bottom
		val centerX = (left + right) / 2
		val centerY = (top + bottom) / 2
		val height = if (isValid) {
			bottom - top + 1
		} else {
			0
		}
	}

	private companion object {
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LANDSCAPE_WIDTH = 780
		const val LANDSCAPE_HEIGHT = 360
		const val CELESTIAL_X_FRACTION = 0.72f
		const val MOON_RADIUS_FRACTION = 0.1f
		const val OPAQUE_ALPHA_THRESHOLD = 245
		const val HALO_MAX_ALPHA = 100
		const val POSITION_TOLERANCE_PIXELS = 2
		val MOON_RADIUS_RANGE = 0.08f..0.12f
	}
}
