package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class SunRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources

	@Test
	fun daytimeSunIsVisibleAsAWarmWhiteLightSource() {
		val bitmap = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams())
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val pixel = bitmap.getPixel(center.x, center.y)

		assertTrue("The daytime sun core should remain fully visible, but alpha was ${Color.alpha(pixel)}", Color.alpha(pixel) >= 250)
		assertTrue("The daytime sun core should remain warm white, but was (${Color.red(pixel)}, ${Color.green(pixel)}, ${Color.blue(pixel)})", minOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)) >= 245 && Color.red(pixel) >= Color.blue(pixel))
		bitmap.recycle()
	}

	@Test
	fun nightSuppressesTheWarmSunWhileKeepingTheMoon() {
		val day = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams())
		val night = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(dayPhase = DayPhase.NIGHT, moonPhase = 0f))
		val dayCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val nightCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.NIGHT)
		val dayPixel = day.getPixel(dayCenter.x, dayCenter.y)
		val nightPixel = night.getPixel(nightCenter.x, nightCenter.y)

		assertTrue("A new-moon night should not retain the opaque daytime sun, but alpha was ${Color.alpha(nightPixel)}", Color.alpha(nightPixel) < Color.alpha(dayPixel) * 0.7f)
		assertTrue("Night light should remain moon-cool rather than sun-warm, but was (${Color.red(nightPixel)}, ${Color.green(nightPixel)}, ${Color.blue(nightPixel)})", Color.blue(nightPixel) >= Color.red(nightPixel))
		day.recycle()
		night.recycle()
	}

	@Test
	fun dawnAndDuskKeepWarmerShouldersThanDay() {
		val day = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams())
		val dawn = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(dayPhase = DayPhase.DAWN))
		val dusk = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(dayPhase = DayPhase.DUSK))
		val dayWarmth = shoulderWarmth(day, DayPhase.DAY)
		val dawnWarmth = shoulderWarmth(dawn, DayPhase.DAWN)
		val duskWarmth = shoulderWarmth(dusk, DayPhase.DUSK)

		assertTrue("Dawn should warm the sun shoulder beyond daytime ($dayWarmth), but measured $dawnWarmth", dawnWarmth > dayWarmth)
		assertTrue("Dusk should remain the warmest phase beyond dawn ($dawnWarmth), but measured $duskWarmth", duskWarmth > dawnWarmth)
		day.recycle()
		dawn.recycle()
		dusk.recycle()
	}

	@Test
	fun sunKeepsReferenceScaleAndPositionInPortraitAndLandscape() {
		assertSunGeometry(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		assertSunGeometry(LANDSCAPE_WIDTH, LANDSCAPE_HEIGHT)
	}

	@Test
	fun fullMoonRetainsItsExistingSizeAndPosition() {
		val bitmap = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(dayPhase = DayPhase.NIGHT, moonPhase = 0.5f))
		val expectedCenter = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.NIGHT)
		val bounds = opaqueBounds(bitmap)
		val radiusFraction = bounds.height / 2f / minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)

		assertTrue("The full moon should remain centered at $expectedCenter, but opaque bounds were $bounds", abs(bounds.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("The full moon should preserve its existing radius near $MOON_RADIUS_FRACTION, but measured $radiusFraction", radiusFraction in MOON_RADIUS_RANGE)
		bitmap.recycle()
	}

	@Test
	fun cloudsAttenuateTheSunAndAvoidSaturatedYellowSlices() {
		val clear = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams())
		val cloudy = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(cloudiness = 0.5f, cloudScale = 1.4f))
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val span = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT)
		val coreDifference = averageColorDistance(clear, cloudy, center, span * CORE_SAMPLE_RADIUS_FRACTION)
		val edgeWarmth = warmestAnnulusPixel(cloudy, center, span * EDGE_INNER_RADIUS_FRACTION, span * EDGE_OUTER_RADIUS_FRACTION)

		assertTrue("Clouds should visibly attenuate the sun instead of sitting behind it, but the core changed by only $coreDifference", coreDifference >= MIN_CLOUD_ATTENUATION)
		assertTrue("Thin clouds should not reveal saturated yellow sun slices, but annulus warmth reached $edgeWarmth", edgeWarmth <= MAX_CLOUDED_EDGE_WARMTH)
		clear.recycle()
		cloudy.recycle()
	}

	@Test
	fun overcastDayKeepsAWarmDiffuseSunAtTheCelestialPosition() {
		assertVeiledSun(clearParams(cloudiness = 0.9f))
	}

	@Test
	fun foggyDayKeepsAWarmDiffuseSunAtTheCelestialPosition() {
		assertVeiledSun(clearParams(fogDensity = 1f))
	}

	@Test
	fun overcastSunFalloffIsIrregularInsteadOfCircular() {
		val bitmap = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(cloudiness = 0.9f, cloudScale = 0f))
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val radius = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT) * ATMOSPHERE_SAMPLE_RADIUS_FRACTION
		val alphaSpread = equalRadiusAlphaSpread(bitmap, center, radius)

		assertTrue("Atmospheric light should not keep equal opacity around a circular radius, but its alpha spread was $alphaSpread", alphaSpread >= MIN_ATMOSPHERE_ALPHA_SPREAD)
		bitmap.recycle()
	}

	@Test
	fun cloudyDayStronglySoftensTheDirectDisc() {
		val bitmap = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(cloudiness = 0.65f, cloudScale = 0f))
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val radius = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT) * VEILED_SAMPLE_RADIUS_FRACTION
		val peakAlpha = highestAlpha(bitmap, center, radius)

		assertTrue("Cloudy daylight should reveal diffuse light without an opaque solar disc, but alpha reached $peakAlpha", peakAlpha < OPAQUE_ALPHA_THRESHOLD)
		bitmap.recycle()
	}

	@Test
	fun precipitationSuppressesTheSunBeforeWeatherComposites() {
		val precipitation = Precipitation(PrecipitationKind.RAIN, severity = 1f, observed = 1f)
		val early = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(precipitation = precipitation, celestialProgress = 0f))
		val midday = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(precipitation = precipitation, celestialProgress = 0.5f))

		assertArrayEquals("Changing the hidden sun position must not alter a precipitation scene", pixels(early), pixels(midday))
		early.recycle()
		midday.recycle()
	}

	@Test
	fun sunTextureGenerationIsDeterministic() {
		val first = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(), TIME_SECONDS)
		val second = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, clearParams(), TIME_SECONDS)

		assertArrayEquals("Equal scene inputs should generate identical sun pixels", pixels(first), pixels(second))
		first.recycle()
		second.recycle()
	}

	private fun assertSunGeometry(width: Int, height: Int) {
		val bitmap = renderForeground(width, height, clearParams())
		val expectedCenter = celestialCenter(width, height, DayPhase.DAY)
		val bounds = opaqueBounds(bitmap)
		val span = minOf(width, height)
		val radiusFraction = bounds.height / 2f / span

		assertTrue("The sun should remain centered at $expectedCenter in ${width}x$height, but opaque bounds were $bounds", abs(bounds.centerX - expectedCenter.x) <= POSITION_TOLERANCE_PIXELS && abs(bounds.centerY - expectedCenter.y) <= POSITION_TOLERANCE_PIXELS)
		assertTrue("The ColorOS-scale sun radius should remain within $SUN_RADIUS_RANGE in ${width}x$height, but measured $radiusFraction", radiusFraction in SUN_RADIUS_RANGE)
		bitmap.recycle()
	}

	private fun assertVeiledSun(params: SceneParams) {
		val early = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params.copy(celestialProgress = 0f))
		val midday = renderForeground(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, params.copy(celestialProgress = 0.5f))
		val center = celestialCenter(PORTRAIT_WIDTH, PORTRAIT_HEIGHT, DayPhase.DAY)
		val radius = minOf(PORTRAIT_WIDTH, PORTRAIT_HEIGHT) * VEILED_SAMPLE_RADIUS_FRACTION
		val displacement = averageColorDistance(early, midday, center, radius)
		val warmthLift = averageWarmthLift(early, midday, center, radius)
		val peakAlpha = highestAlpha(midday, center, radius)

		assertTrue("Moving the obscured sun should move visible diffuse light, but the midday region changed by only $displacement", displacement >= MIN_VEILED_DISPLACEMENT)
		assertTrue("The obscured sun should lift warm light rather than neutral haze, but its warm lift was $warmthLift", warmthLift >= MIN_VEILED_WARMTH_LIFT)
		assertTrue("The obscured sun should remain diffuse without an opaque disc or streak, but alpha reached $peakAlpha", peakAlpha < OPAQUE_ALPHA_THRESHOLD)
		early.recycle()
		midday.recycle()
	}

	private fun averageWarmthLift(first: Bitmap, second: Bitmap, center: PixelPoint, radius: Float): Float {
		val radiusSquared = radius * radius
		var total = 0L
		var count = 0

		for (y in (center.y - radius.toInt())..(center.y + radius.toInt())) {
			for (x in (center.x - radius.toInt())..(center.x + radius.toInt())) {
				val dx = x - center.x
				val dy = y - center.y
				if (dx * dx + dy * dy > radiusSquared) {
					continue
				}

				val firstPixel = first.getPixel(x, y)
				val secondPixel = second.getPixel(x, y)
				total += Color.red(secondPixel) - Color.red(firstPixel) - (Color.blue(secondPixel) - Color.blue(firstPixel))
				count++
			}
		}

		return total.toFloat() / count.coerceAtLeast(1)
	}

	private fun highestAlpha(bitmap: Bitmap, center: PixelPoint, radius: Float): Int {
		val radiusSquared = radius * radius
		var peak = 0

		for (y in (center.y - radius.toInt())..(center.y + radius.toInt())) {
			for (x in (center.x - radius.toInt())..(center.x + radius.toInt())) {
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

	private fun equalRadiusAlphaSpread(bitmap: Bitmap, center: PixelPoint, radius: Float): Int {
		val alphas = IntArray(ATMOSPHERE_DIRECTION_COUNT) { index ->
			val angle = TAU * index / ATMOSPHERE_DIRECTION_COUNT
			val x = center.x + (cos(angle) * radius).roundToInt()
			val y = center.y + (sin(angle) * radius).roundToInt()

			Color.alpha(bitmap.getPixel(x, y))
		}

		return alphas.maxOrNull()!! - alphas.minOrNull()!!
	}

	private fun shoulderWarmth(bitmap: Bitmap, dayPhase: DayPhase): Int {
		val center = celestialCenter(bitmap.width, bitmap.height, dayPhase)
		val offset = (minOf(bitmap.width, bitmap.height) * SHOULDER_SAMPLE_FRACTION).roundToInt()
		val pixel = bitmap.getPixel(center.x, center.y + offset)

		return Color.red(pixel) - Color.blue(pixel)
	}

	private fun averageColorDistance(first: Bitmap, second: Bitmap, center: PixelPoint, radius: Float): Float {
		val radiusSquared = radius * radius
		var total = 0L
		var count = 0

		for (y in (center.y - radius.toInt())..(center.y + radius.toInt())) {
			for (x in (center.x - radius.toInt())..(center.x + radius.toInt())) {
				val dx = x - center.x
				val dy = y - center.y
				if (dx * dx + dy * dy > radiusSquared) {
					continue
				}

				total += colorDistance(first.getPixel(x, y), second.getPixel(x, y))
				count++
			}
		}

		return total.toFloat() / count.coerceAtLeast(1)
	}

	private fun warmestAnnulusPixel(bitmap: Bitmap, center: PixelPoint, innerRadius: Float, outerRadius: Float): Int {
		val innerSquared = innerRadius * innerRadius
		val outerSquared = outerRadius * outerRadius
		var warmth = Int.MIN_VALUE

		for (y in (center.y - outerRadius.toInt())..(center.y + outerRadius.toInt())) {
			for (x in (center.x - outerRadius.toInt())..(center.x + outerRadius.toInt())) {
				val dx = x - center.x
				val dy = y - center.y
				val distanceSquared = dx * dx + dy * dy
				if (distanceSquared < innerSquared || distanceSquared > outerSquared) {
					continue
				}

				val pixel = bitmap.getPixel(x, y)
				warmth = maxOf(warmth, Color.red(pixel) - Color.blue(pixel))
			}
		}

		return warmth
	}

	private fun opaqueBounds(bitmap: Bitmap): PixelBounds {
		var left = bitmap.width
		var top = bitmap.height
		var right = -1
		var bottom = -1

		for (y in 0 until bitmap.height) {
			for (x in 0 until bitmap.width) {
				if (Color.alpha(bitmap.getPixel(x, y)) < OPAQUE_ALPHA_THRESHOLD) {
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

	private fun celestialCenter(width: Int, height: Int, dayPhase: DayPhase) = PixelPoint(
		x = (width * CELESTIAL_X_FRACTION).roundToInt(),
		y = (height * celestialHeight(dayPhase)).roundToInt(),
	)

	private fun celestialHeight(dayPhase: DayPhase) = when (dayPhase) {
		DayPhase.DAY -> MIDDAY_HEIGHT_FRACTION
		DayPhase.DAWN -> DAWN_DUSK_MIDPOINT_HEIGHT_FRACTION
		DayPhase.DUSK -> DAWN_DUSK_MIDPOINT_HEIGHT_FRACTION
		DayPhase.NIGHT -> NIGHT_HEIGHT_FRACTION
	}

	private fun clearParams(
		dayPhase: DayPhase = DayPhase.DAY,
		cloudiness: Float = 0f,
		fogDensity: Float = 0f,
		cloudScale: Float = 1f,
		precipitation: Precipitation? = null,
		moonPhase: Float = 0.5f,
		celestialProgress: Float = 0.5f,
	) = SceneParams(
		dayPhase = dayPhase,
		cloudiness = cloudiness,
		fogDensity = fogDensity,
		precipitation = precipitation,
		thunder = false,
		windFactor = 0.2f,
		cloudScale = cloudScale,
		moonPhase = moonPhase,
		celestialProgress = celestialProgress,
	)

	private fun renderForeground(width: Int, height: Int, params: SceneParams, timeSeconds: Float = 0f): Bitmap {
		val bitmap = createBitmap(width, height)
		SceneRenderer(resources).renderForeground(Canvas(bitmap), width, height, params, timeSeconds)

		return bitmap
	}

	private fun pixels(bitmap: Bitmap): IntArray {
		return IntArray(bitmap.width * bitmap.height).also {
			bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
		}
	}

	private fun colorDistance(first: Int, second: Int): Int {
		return maxOf(
			abs(Color.red(first) - Color.red(second)),
			abs(Color.green(first) - Color.green(second)),
			abs(Color.blue(first) - Color.blue(second)),
		)
	}

	private data class PixelPoint(val x: Int, val y: Int)

	private data class PixelBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
		val centerX = (left + right) / 2
		val centerY = (top + bottom) / 2
		val height = bottom - top + 1
	}

	private companion object {
		const val PORTRAIT_WIDTH = 360
		const val PORTRAIT_HEIGHT = 780
		const val LANDSCAPE_WIDTH = 780
		const val LANDSCAPE_HEIGHT = 360
		const val TIME_SECONDS = 17f
		const val CELESTIAL_X_FRACTION = 0.72f
		const val MIDDAY_HEIGHT_FRACTION = 0.17f
		const val DAWN_DUSK_MIDPOINT_HEIGHT_FRACTION = 0.34f
		const val NIGHT_HEIGHT_FRACTION = 0.24f
		const val SHOULDER_SAMPLE_FRACTION = 0.055f
		const val CORE_SAMPLE_RADIUS_FRACTION = 0.05f
		const val EDGE_INNER_RADIUS_FRACTION = 0.055f
		const val EDGE_OUTER_RADIUS_FRACTION = 0.11f
		const val VEILED_SAMPLE_RADIUS_FRACTION = 0.045f
		const val ATMOSPHERE_SAMPLE_RADIUS_FRACTION = 0.12f
		const val OPAQUE_ALPHA_THRESHOLD = 245
		const val POSITION_TOLERANCE_PIXELS = 2
		const val MIN_CLOUD_ATTENUATION = 2f
		const val MIN_VEILED_DISPLACEMENT = 1.5f
		const val MIN_VEILED_WARMTH_LIFT = 0.5f
		const val MIN_ATMOSPHERE_ALPHA_SPREAD = 8
		const val MAX_CLOUDED_EDGE_WARMTH = 52
		const val ATMOSPHERE_DIRECTION_COUNT = 12
		const val TAU = 2.0 * PI
		const val MOON_RADIUS_FRACTION = 0.1f
		val SUN_RADIUS_RANGE = 0.062f..0.082f
		val MOON_RADIUS_RANGE = 0.08f..0.12f
	}
}
