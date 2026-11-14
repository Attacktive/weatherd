package xyz.attacktive.weatherd.domain.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.attacktive.weatherd.BuildConfig
import xyz.attacktive.weatherd.domain.model.DayPhase
import xyz.attacktive.weatherd.domain.model.Precipitation
import xyz.attacktive.weatherd.domain.model.PrecipitationKind

@RunWith(AndroidJUnit4::class)
class StormCloudPrototypeRenderingTest {
	private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
	private val rainy = SceneParams(DayPhase.DAY, 1f, 0f, Precipitation(PrecipitationKind.RAIN, 0.5f, 1f), false, 0.5f)

	@Test
	fun disabledPrototypeDoesNotDecodeOrTakeOverAnyWeather() {
		val prototype = StormCloudPrototype(resources, enabled = false) { _, _ -> error("Disabled prototype decoded an asset") }
		prototype.prewarm()
		for (precipitation in listOf(rainy.precipitation, null, Precipitation(PrecipitationKind.SNOW, 0.5f, 1f), Precipitation(PrecipitationKind.SLEET, 0.5f, 1f))) {
			assertFalse(prototype.draw(Canvas(), 480f, 1056f, rainy.copy(precipitation = precipitation), 0f, Color.WHITE, CloudColorGrade.IDENTITY))
		}
	}

	@Test
	fun drawNeverDecodesAndPrewarmingPublishesAllThreeLayersTogether() {
		var decodes = 0
		val sources = mutableListOf<Bitmap>()
		val prototype = StormCloudPrototype(resources, enabled = true) { _, _ ->
			decodes++
			fixture().also { sources.add(it) }
		}

		try {
			assertFalse(prototype.draw(Canvas(), 480f, 1056f, rainy, 0f, Color.WHITE, CloudColorGrade.IDENTITY))
			assertEquals(0, decodes)
			prototype.prewarm()
			prototype.prewarm()
			assertEquals(4, decodes)
			assertTrue(prototype.draw(Canvas(), 480f, 1056f, rainy, 30f, Color.WHITE, CloudColorGrade.IDENTITY))
			assertEquals(4, decodes)
		} finally {
			sources.forEach { it.recycle() }
		}
	}

	@Test
	fun overcastSnowAndSleetUsePreparedStormClouds() {
		val sources = mutableListOf<Bitmap>()
		val prototype = StormCloudPrototype(resources, enabled = true) { _, _ -> fixture().also { sources.add(it) } }
		val target = createBitmap(480, 1056)
		val pixels = IntArray(480 * 1056)
		try {
			prototype.prewarm()
			for (precipitation in listOf(null, Precipitation(PrecipitationKind.SNOW, 0.5f, 1f), Precipitation(PrecipitationKind.SLEET, 0.5f, 1f))) {
				target.eraseColor(Color.TRANSPARENT)
				assertTrue(prototype.draw(Canvas(target), 480f, 1056f, rainy.copy(precipitation = precipitation), 0f, Color.WHITE, CloudColorGrade.IDENTITY))
				target.getPixels(pixels, 0, 480, 0, 0, 480, 1056)
				assertTrue("Expected visible storm clouds for $precipitation", pixels.any { Color.alpha(it) > 0 })
			}
		} finally {
			target.recycle()
			sources.forEach { it.recycle() }
		}
	}

	@Test
	fun transparentPixelsDoNotPaintRectanglesAndZeroOpacityDrawsNothing() {
		val sources = mutableListOf<Bitmap>()
		val prototype = StormCloudPrototype(resources, enabled = true) { _, _ -> fixture().also { sources.add(it) } }
		val target = createBitmap(480, 1056)
		try {
			prototype.prewarm()
			assertTrue(prototype.draw(Canvas(target), 480f, 1056f, rainy, 0f, Color.WHITE, CloudColorGrade.IDENTITY))
			assertEquals(0, Color.alpha(target.getPixel(0, 1055)))
			val pixels = IntArray(480 * 1056)
			target.getPixels(pixels, 0, 480, 0, 0, 480, 1056)
			assertTrue(pixels.any { Color.alpha(it) > 0 })
			assertTrue(pixels.any { Color.alpha(it) in 1..254 })
			target.eraseColor(Color.TRANSPARENT)
			assertTrue(prototype.draw(Canvas(target), 480f, 1056f, rainy.copy(cloudScale = 0f), 0f, Color.WHITE, CloudColorGrade.IDENTITY))
			target.getPixels(pixels, 0, 480, 0, 0, 480, 1056)
			assertTrue(pixels.all { Color.alpha(it) == 0 })
		} finally {
			target.recycle()
			sources.forEach { it.recycle() }
		}
	}

	@Test
	fun atmosphereIsOptInAndLeavesNightUnchanged() {
		val baseline = naturalSkyGradientFor(rainy)
		assertEquals(baseline, prototypeSkyGradient(rainy, baseline, enabled = false))
		assertFalse(baseline == prototypeSkyGradient(rainy, baseline, enabled = true))
		val night = rainy.copy(dayPhase = DayPhase.NIGHT)
		val nightSky = naturalSkyGradientFor(night)
		assertEquals(nightSky, prototypeSkyGradient(night, nightSky, enabled = true))
	}

	@Test
	fun optedInBuildDecodesRealPackagedClouds() {
		assumeTrue(BuildConfig.STORM_CLOUD_PROTOTYPE)
		val prototype = StormCloudPrototype(resources)
		val target = createBitmap(480, 1056)

		try {
			prototype.prewarm()
			assertTrue(prototype.draw(Canvas(target), 480f, 1056f, rainy, 30f, Color.WHITE, CloudColorGrade.IDENTITY))
			val pixels = IntArray(480 * 1056)
			target.getPixels(pixels, 0, 480, 0, 0, 480, 1056)
			assertTrue(pixels.any { Color.alpha(it) > 0 })
		} finally {
			target.recycle()
		}
	}

	private fun fixture() = createBitmap(160, 80).apply {
		Canvas(this).drawOval(16f, 8f, 144f, 72f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(192, 130, 145, 160) })
	}
}
