package xyz.attacktive.weatherd.domain.render

import kotlin.math.roundToInt
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind
import xyz.attacktive.weatherd.domain.weather.SEVERITY_DRIZZLE
import xyz.attacktive.weatherd.domain.weather.SEVERITY_STEADY

@RunWith(AndroidJUnit4::class)
class GlassDropletRenderingTest {
	@Test
	fun beadTransmitsBackgroundAndHasGlassHighlights() {
		val layer = GlassDropletLayer()
		val params = rainParams(scale = 0.1f, observed = 0f, severity = SEVERITY_DRIZZLE)
		val time = findSampleTime {
			it.opacity > 0.99f && it.verticalStretch == 1f && isFullyVisible(it)
		}
		val frame = GlassDropletFrame()
		frame.sample(0, WIDTH.toFloat(), HEIGHT.toFloat(), time)

		val dark = render(layer, Color.rgb(24, 28, 34), params, time)
		val light = render(layer, Color.rgb(236, 240, 244), params, time)
		val sampleRadius = maxOf(1, (frame.radius * 0.12f).roundToInt())
		val bodyDark = brightnessAround(dark, frame.centerX, frame.centerY, sampleRadius)
		val bodyLight = brightnessAround(light, frame.centerX, frame.centerY, sampleRadius)
		val highlight = brightnessAround(
			dark,
			frame.centerX - frame.radius * 0.3f,
			frame.centerY - frame.radius * 0.32f,
			sampleRadius
		)
		val lowerRim = brightnessAround(
			light,
			frame.centerX,
			frame.centerY + frame.radius * 0.9f,
			sampleRadius
		)
		val neighboringLight = brightnessAround(
			light,
			frame.centerX + frame.radius * 1.7f,
			frame.centerY,
			sampleRadius
		)

		assertTrue("A translucent body must preserve a strong background response: $bodyDark -> $bodyLight", bodyLight > bodyDark + 80f)
		assertTrue("The upper glass highlight must be brighter than the body: $bodyDark -> $highlight", highlight > bodyDark + 18f)
		assertTrue("The lower rim must darken the neighboring light background: $lowerRim vs $neighboringLight", lowerRim < neighboringLight - 12f)

		dark.recycle()
		light.recycle()
	}

	@Test
	fun slidingRimFollowsBodyWithoutDetachedArc() {
		val layer = GlassDropletLayer()
		val params = rainParams(scale = 0.1f, observed = 0f, severity = SEVERITY_DRIZZLE)
		val time = findSampleTime(MATERIAL_SIZE, MATERIAL_SIZE) {
			it.opacity > 0.99f && it.verticalStretch > 1.6f && it.radius > MATERIAL_MIN_RADIUS && isFullyVisible(it, MATERIAL_SIZE, MATERIAL_SIZE)
		}
		val frame = GlassDropletFrame()
		frame.sample(0, MATERIAL_SIZE.toFloat(), MATERIAL_SIZE.toFloat(), time)

		val bitmap = render(layer, Color.WHITE, params, time, width = MATERIAL_SIZE, height = MATERIAL_SIZE)
		val halfHeight = frame.radius * frame.verticalStretch
		val contourMidpointY = frame.centerY + halfHeight * LOWER_CONTOUR_MIDPOINT_Y_FRACTION
		val detachedArcProbeY = frame.centerY + halfHeight * DETACHED_ARC_PROBE_Y_FRACTION
		val rimBrightness = brightnessAround(bitmap, frame.centerX, contourMidpointY, 0)
		val belowBodyBrightness = brightnessAround(bitmap, frame.centerX, detachedArcProbeY, 0)

		assertTrue("The sliding rim must darken the cubic body's lower contour: $rimBrightness", rimBrightness < 230f)
		assertTrue("No detached elliptical rim may remain below the cubic body: $belowBodyBrightness", belowBodyBrightness > 250f)

		bitmap.recycle()
	}

	@Test
	fun nonRainDoesNotLeaveGlassPixels() {
		val layer = GlassDropletLayer()
		val rainy = rainParams(scale = 1f, observed = 1f, severity = SEVERITY_STEADY)
		val bitmap = createBitmap(TEST_WIDTH, TEST_HEIGHT)
		val reference = createBitmap(TEST_WIDTH, TEST_HEIGHT)
		val background = Color.rgb(72, 88, 104)
		bitmap.eraseColor(background)
		reference.eraseColor(background)

		layer.draw(Canvas(bitmap), TEST_WIDTH.toFloat(), TEST_HEIGHT.toFloat(), rainy, 7f)

		val nonRain = arrayOf(
			rainy.copy(precipitation = null),
			rainy.copy(precipitation = rainy.precipitation?.copy(kind = PrecipitationKind.SNOW)),
			rainy.copy(precipitation = rainy.precipitation?.copy(kind = PrecipitationKind.SLEET)),
			rainy.copy(precipitationScale = 0f)
		)
		for (params in nonRain) {
			bitmap.eraseColor(background)
			layer.draw(Canvas(bitmap), TEST_WIDTH.toFloat(), TEST_HEIGHT.toFloat(), params, 7f)
			assertTrue("Non-rain drawing must leave the supplied bitmap untouched", bitmap.sameAs(reference))
		}

		bitmap.recycle()
		reference.recycle()
	}

	@Test
	fun reusedLayerPreservesGeometryAndMaterial() {
		val reused = GlassDropletLayer()
		val low = rainParams(scale = 0.1f, observed = 0.25f, severity = SEVERITY_DRIZZLE)
		val high = rainParams(scale = 2f, observed = 1f, severity = SEVERITY_STEADY)
		val dry = low.copy(precipitation = null)
		val time = findSampleTime {
			it.opacity > 0.99f && isFullyVisible(it)
		}

		render(reused, Color.BLACK, low, time).recycle()
		render(reused, Color.BLACK, dry, time).recycle()
		render(reused, Color.BLACK, high, time).recycle()
		render(reused, Color.BLACK, low, time).recycle()
		render(reused, Color.BLACK, low, time, width = HEIGHT, height = WIDTH).recycle()
		val reusedFinal = render(reused, Color.BLACK, low, time)
		val freshFinal = render(GlassDropletLayer(), Color.BLACK, low, time)

		assertTrue("A reused layer must reproduce the same portrait frame after weather, intensity, and size changes", reusedFinal.sameAs(freshFinal))

		reusedFinal.recycle()
		freshFinal.recycle()
	}

	private fun rainParams(scale: Float, observed: Float, severity: Float) = SceneParams(
		dayPhase = DayPhase.DAY,
		cloudiness = 0f,
		fogDensity = 0f,
		precipitation = Precipitation(PrecipitationKind.RAIN, severity, observed),
		thunder = false,
		windFactor = 0f,
		precipitationScale = scale
	)

	private fun findSampleTime(width: Int = WIDTH, height: Int = HEIGHT, predicate: (GlassDropletFrame) -> Boolean): Float {
		val frame = GlassDropletFrame()
		for (step in 0..384) {
			val time = step * 0.25f
			frame.sample(0, width.toFloat(), height.toFloat(), time)
			if (predicate(frame)) {
				return time
			}
		}

		error("No suitable deterministic glass-droplet sample found")
	}

	private fun isFullyVisible(frame: GlassDropletFrame, width: Int = WIDTH, height: Int = HEIGHT): Boolean {
		val halfHeight = frame.radius * frame.verticalStretch
		val horizontallyVisible = frame.centerX - frame.radius >= 0f && frame.centerX + frame.radius < width
		val verticallyVisible = frame.centerY - halfHeight >= 0f && frame.centerY + halfHeight < height

		return horizontallyVisible && verticallyVisible
	}

	private fun render(
		layer: GlassDropletLayer,
		background: Int,
		params: SceneParams,
		time: Float,
		width: Int = WIDTH,
		height: Int = HEIGHT
	): Bitmap {
		val bitmap = createBitmap(width, height)
		bitmap.eraseColor(background)
		layer.draw(Canvas(bitmap), width.toFloat(), height.toFloat(), params, time)

		return bitmap
	}

	private fun brightnessAround(bitmap: Bitmap, centerX: Float, centerY: Float, radius: Int): Float {
		val minX = (centerX.roundToInt() - radius).coerceIn(0, bitmap.width - 1)
		val maxX = (centerX.roundToInt() + radius).coerceIn(0, bitmap.width - 1)
		val minY = (centerY.roundToInt() - radius).coerceIn(0, bitmap.height - 1)
		val maxY = (centerY.roundToInt() + radius).coerceIn(0, bitmap.height - 1)
		var brightness = 0f
		var count = 0

		for (y in minY..maxY) {
			for (x in minX..maxX) {
				val color = bitmap.getPixel(x, y)
				brightness += (Color.red(color) + Color.green(color) + Color.blue(color)) / 3f
				count += 1
			}
		}

		return brightness / count
	}

	private companion object {
		const val WIDTH = 720
		const val HEIGHT = 1560
		const val TEST_WIDTH = 360
		const val TEST_HEIGHT = 780
		const val MATERIAL_SIZE = 1600
		const val MATERIAL_MIN_RADIUS = 14f
		const val LOWER_CONTOUR_MIDPOINT_Y_FRACTION = 0.905f
		const val DETACHED_ARC_PROBE_Y_FRACTION = 0.99f
	}
}
